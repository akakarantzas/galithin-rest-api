package com.galithin.web.sources.text

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class RichTextRendererTest {
    private val renderer = RichTextRenderer(
        catalogVersion = { "catalog-test" },
        glyphs = {
            GlyphRegistry(
                listOf(GlyphDefinition("mana", 0xE001, "assets/galithin/textures/glyph/mana.png", 9, 9, 8)),
                mapOf(0xE103 to -4, 0xE114 to 4),
            )
        },
    )

    @Test
    fun `renders decorations gradient and canonical adventure json`() {
        val rendered = renderer.fromMiniMessage("<gradient:#ff0000:#00ff00><bold>Power</bold></gradient>")

        assertEquals("Power", rendered.plainText)
        assertContains(rendered.adventureJson.lowercase(), "#ff0000")
        assertContains(rendered.sanitizedHtml, "font-weight:bold")
        assertFalse(rendered.sanitizedHtml.contains("<script"))
    }

    @Test
    fun `maps every blocky font variant to the website font`() {
        val base = renderer.fromMiniMessage("<font:blocky>Implicits:</font>")
        val inventory = renderer.fromAdventureJson(
            """{"text":"Prefixes:","font":"minecraft:blockyinventory4"}""",
        )

        assertContains(base.sanitizedHtml, "font-family:var(--font-blocky-source),Minecraft,monospace")
        assertContains(inventory.sanitizedHtml, "font-family:var(--font-blocky-source),Minecraft,monospace")
    }

    @Test
    fun `renders custom glyph and signed shift as controlled nodes`() {
        val rendered = renderer.fromMiniMessage("A<shift:-3><glyph:mana>B")

        assertEquals("A\uE001B", rendered.plainText)
        assertContains(rendered.sanitizedHtml, "class=\"galithin-shift\"")
        assertContains(rendered.sanitizedHtml, "width:-3px")
        assertContains(rendered.sanitizedHtml, "class=\"galithin-glyph\"")
        assertContains(rendered.sanitizedHtml, "/assets/catalog-test/assets/galithin/textures/glyph/mana.png")
    }

    @Test
    fun `renders raw Nexo space glyphs as controlled shift nodes`() {
        val rendered = renderer.fromAdventureJson("""{"text":"A\uE103B\uE114C"}""")

        assertEquals("ABC", rendered.plainText)
        assertContains(rendered.sanitizedHtml, "width:-4px")
        assertContains(rendered.sanitizedHtml, "width:4px")
        assertFalse(rendered.sanitizedHtml.contains("\uE103"))
        assertFalse(rendered.sanitizedHtml.contains("\uE114"))
    }

    @Test
    fun `escapes arbitrary text instead of emitting html`() {
        val rendered = renderer.fromPlain("<img src=x onerror=alert(1)> & text")

        assertContains(rendered.sanitizedHtml, "&lt;img")
        assertContains(rendered.sanitizedHtml, "&amp; text")
        assertFalse(rendered.sanitizedHtml.contains("<img src="))
    }

    @Test
    fun `rejects referenced glyphs missing from the bundle`() {
        assertFailsWith<IllegalArgumentException> {
            renderer.fromMiniMessage("<glyph:missing>")
        }
    }
}
