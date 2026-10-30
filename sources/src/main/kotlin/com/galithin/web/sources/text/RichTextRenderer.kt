package com.galithin.web.sources.text

import com.fasterxml.jackson.databind.JsonNode
import com.galithin.web.domain.RichText
import com.galithin.web.sources.objectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

@Serializable
data class GlyphDefinition(
    val id: String,
    val codePoint: Int,
    val assetPath: String,
    val width: Int,
    val height: Int,
    val ascent: Int,
)

class GlyphRegistry(
    definitions: Collection<GlyphDefinition> = emptyList(),
    shiftAdvances: Map<Int, Int> = emptyMap(),
) {
    private val byId = definitions.associateBy { it.id }
    private val byCodePoint = definitions.associateBy { it.codePoint }
    private val shiftsByCodePoint = shiftAdvances.toMap()

    fun named(id: String): GlyphDefinition? = byId[id]

    fun codePoint(codePoint: Int): GlyphDefinition? = byCodePoint[codePoint]

    fun shift(codePoint: Int): Int? = shiftsByCodePoint[codePoint]
}

class RichTextRenderer(
    private val catalogVersion: () -> String,
    private val glyphs: () -> GlyphRegistry = { GlyphRegistry() },
) {
    fun fromMiniMessage(source: String): RichText {
        val prepared = replaceGalithinTags(source)
        val component = MiniMessage.miniMessage().deserialize(prepared)
        return fromComponent(component, source)
    }

    fun fromAdventureJson(json: String): RichText =
        try {
            fromComponent(GsonComponentSerializer.gson().deserialize(json), null)
        } catch (_: RuntimeException) {
            fromPlain(json)
        }

    fun fromPlain(value: String): RichText = fromComponent(Component.text(value), null)

    private fun fromComponent(component: Component, source: String?): RichText {
        val json = GsonComponentSerializer.gson().serialize(component)
        return RichText(
            adventureJson = json,
            plainText = PlainTextComponentSerializer.plainText().serialize(component).removeShiftMarkers(glyphs()),
            sanitizedHtml = renderNode(objectMapper.readTree(json), HtmlStyle()),
            sourceMiniMessage = source,
        )
    }

    private fun replaceGalithinTags(value: String): String {
        var result = GLYPH_TAG.replace(value) { match ->
            val id = match.groupValues[1]
            val glyph = requireNotNull(glyphs().named(id)) { "Unknown web glyph '$id'" }
            Character.toString(glyph.codePoint)
        }
        result = SHIFT_TAG.replace(result) { match ->
            val pixels = match.groupValues[1].toIntOrNull()?.coerceIn(-128, 128) ?: 0
            Character.toString(SHIFT_MARKER_BASE + pixels + 128)
        }
        return result
    }

    private fun renderNode(node: JsonNode, inherited: HtmlStyle): String {
        if (node.isTextual) return renderText(node.textValue(), inherited)
        if (node.isArray) return node.joinToString("") { renderNode(it, inherited) }
        val style = inherited.merge(node)
        val content = node.get("text")?.takeIf { it.isTextual }?.textValue()
            ?: node.get("translate")?.takeIf { it.isTextual }?.textValue()
            ?: ""
        val rendered = renderText(content, style)
        val children = node.get("extra")
            ?.takeIf { it.isArray }
            ?.joinToString("") { renderNode(it, style) }
            .orEmpty()
        return rendered + children
    }

    private fun renderText(value: String, style: HtmlStyle): String {
        if (value.isEmpty()) return ""
        val result = StringBuilder()
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isEmpty()) return
            val css = style.css()
            result.append("<span")
            if (css.isNotEmpty()) result.append(" style=\"").append(css.escapeAttribute()).append("\"")
            result.append(">").append(plain.toString().escapeHtml()).append("</span>")
            plain.clear()
        }

        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val shift = markerShiftPixels(codePoint) ?: glyphs().shift(codePoint)
            val glyph = glyphs().codePoint(codePoint)
            when {
                shift != null -> {
                    flushPlain()
                    result.append("<span aria-hidden=\"true\" class=\"galithin-shift\" style=\"display:inline-block;width:")
                        .append(shift)
                        .append("px\"></span>")
                }
                glyph != null -> {
                    flushPlain()
                    val path = glyph.assetPath.split('/').joinToString("/") {
                        URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20")
                    }
                    result.append("<img class=\"galithin-glyph\" alt=\"")
                        .append(glyph.id.escapeAttribute())
                        .append("\" src=\"/assets/")
                        .append(catalogVersion().escapeAttribute())
                        .append('/').append(path.escapeAttribute())
                        .append("\" width=\"").append(glyph.width)
                        .append("\" height=\"").append(glyph.height)
                        .append("\" style=\"vertical-align:")
                        .append(-(glyph.height - glyph.ascent))
                        .append("px\">")
                }
                else -> plain.appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
        flushPlain()
        return result.toString()
    }

    private data class HtmlStyle(
        val color: String? = null,
        val font: String? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underlined: Boolean? = null,
        val strikethrough: Boolean? = null,
        val obfuscated: Boolean? = null,
        val shadowColor: String? = null,
    ) {
        fun merge(node: JsonNode) = copy(
            color = node.get("color")?.takeIf { it.isTextual }?.textValue() ?: color,
            font = node.get("font")?.takeIf { it.isTextual }?.textValue() ?: font,
            bold = node.booleanOrNull("bold") ?: bold,
            italic = node.booleanOrNull("italic") ?: italic,
            underlined = node.booleanOrNull("underlined") ?: underlined,
            strikethrough = node.booleanOrNull("strikethrough") ?: strikethrough,
            obfuscated = node.booleanOrNull("obfuscated") ?: obfuscated,
            shadowColor = node.get("shadow_color")?.takeIf { it.isTextual }?.textValue() ?: shadowColor,
        )

        fun css(): String = buildList {
            color?.takeIf(::isSafeColor)?.let { add("color:$it") }
            font?.takeIf(::isSafeFont)?.let {
                val family = if (it.substringAfter(':').startsWith("blocky")) {
                    "var(--font-blocky-source)"
                } else {
                    "'${it.replace("'", "")}'"
                }
                add("font-family:$family,Minecraft,monospace")
            }
            bold?.let { add("font-weight:${if (it) "bold" else "normal"}") }
            italic?.let { add("font-style:${if (it) "italic" else "normal"}") }
            val decorations = buildList {
                if (underlined == true) add("underline")
                if (strikethrough == true) add("line-through")
            }
            if (decorations.isNotEmpty()) add("text-decoration:${decorations.joinToString(" ")}")
            if (underlined == false && strikethrough == false) add("text-decoration:none")
            if (obfuscated == true) add("filter:blur(3px)")
            shadowColor?.takeIf(::isSafeColor)?.let { add("text-shadow:3px 3px $it") }
        }.joinToString(";")
    }

    companion object {
        private const val SHIFT_MARKER_BASE = 0xF0000
        private val GLYPH_TAG = Regex("<glyph:([a-zA-Z0-9_.-]+)>")
        private val SHIFT_TAG = Regex("<shift:([-+]?\\d+)>")
        private val SAFE_COLOR = Regex("^(#[0-9a-fA-F]{6,8}|[a-zA-Z]{3,24})$")
        private val SAFE_FONT = Regex("^[a-zA-Z0-9_.:/-]{1,128}$")

        private fun JsonNode.booleanOrNull(name: String): Boolean? =
            get(name)?.takeIf { it.isBoolean }?.booleanValue()

        private fun isSafeColor(value: String) = SAFE_COLOR.matches(value)

        private fun isSafeFont(value: String) = SAFE_FONT.matches(value)

        private fun String.escapeHtml(): String =
            replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")

        private fun String.escapeAttribute(): String = escapeHtml()

        private fun markerShiftPixels(codePoint: Int): Int? =
            if (codePoint in SHIFT_MARKER_BASE..(SHIFT_MARKER_BASE + 256)) {
                codePoint - SHIFT_MARKER_BASE - 128
            } else {
                null
            }

        private fun String.removeShiftMarkers(registry: GlyphRegistry): String = buildString {
            var offset = 0
            while (offset < this@removeShiftMarkers.length) {
                val codePoint = this@removeShiftMarkers.codePointAt(offset)
                if (markerShiftPixels(codePoint) == null && registry.shift(codePoint) == null) {
                    appendCodePoint(codePoint)
                }
                offset += Character.charCount(codePoint)
            }
        }
    }
}
