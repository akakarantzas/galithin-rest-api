package com.galithin.web.sources.husksync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kyori.adventure.nbt.BinaryTag
import net.kyori.adventure.nbt.ByteArrayBinaryTag
import net.kyori.adventure.nbt.ByteBinaryTag
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.DoubleBinaryTag
import net.kyori.adventure.nbt.FloatBinaryTag
import net.kyori.adventure.nbt.IntArrayBinaryTag
import net.kyori.adventure.nbt.IntBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.LongArrayBinaryTag
import net.kyori.adventure.nbt.LongBinaryTag
import net.kyori.adventure.nbt.ShortBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import net.kyori.adventure.nbt.TagStringIO

internal object Snbt {
    private val io = TagStringIO.tagStringIO()

    fun parse(value: String): CompoundBinaryTag =
        try {
            io.asCompound(value)
        } catch (original: Exception) {
            try {
                io.asCompound(sanitizeTextComponentExtraLists(value))
            } catch (_: Exception) {
                throw original
            }
        }

    /**
     * Parses a named compound independently from the rest of an SNBT document.
     *
     * Real item components can contain heterogeneous text `extra` lists. If one
     * unrelated value makes the outer persistent-data compound invalid to
     * Adventure NBT, Core sync data should still be recoverable on its own.
     */
    fun parseCompound(value: String, localKey: String): CompoundBinaryTag? {
        val raw = extractCompound(value, localKey) ?: return null
        return try {
            parse(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun stringify(tag: BinaryTag): String = io.asString(tag)

    fun CompoundBinaryTag.local(key: String): BinaryTag? {
        get(key)?.let { return it }
        get("galithin-core:$key")?.let { return it }
        keySet().firstOrNull { it.substringAfter(':') == key }?.let { return get(it) }
        return null
    }

    fun CompoundBinaryTag.compound(key: String): CompoundBinaryTag? = local(key) as? CompoundBinaryTag

    fun CompoundBinaryTag.string(key: String): String? = (local(key) as? StringBinaryTag)?.value()

    fun CompoundBinaryTag.int(key: String): Int? = number(key)?.toInt()

    fun CompoundBinaryTag.long(key: String): Long? = number(key)?.toLong()

    fun CompoundBinaryTag.number(key: String): Number? =
        when (val value = local(key)) {
            is ByteBinaryTag -> value.value()
            is ShortBinaryTag -> value.value()
            is IntBinaryTag -> value.value()
            is LongBinaryTag -> value.value()
            is FloatBinaryTag -> value.value()
            is DoubleBinaryTag -> value.value()
            else -> null
        }

    fun CompoundBinaryTag.list(key: String): ListBinaryTag? = local(key) as? ListBinaryTag

    fun CompoundBinaryTag.findCompound(key: String): CompoundBinaryTag? {
        compound(key)?.let { return it }
        for (entry in keySet()) {
            val child = get(entry) as? CompoundBinaryTag ?: continue
            child.findCompound(key)?.let { return it }
        }
        return null
    }

    fun BinaryTag.toJsonElement(): JsonElement =
        when (this) {
            is CompoundBinaryTag -> JsonObject(keySet().associateWith { get(it)?.toJsonElement() ?: JsonNull })
            is ListBinaryTag -> JsonArray(map { it.toJsonElement() })
            is StringBinaryTag -> JsonPrimitive(value())
            is ByteBinaryTag -> JsonPrimitive(value())
            is ShortBinaryTag -> JsonPrimitive(value())
            is IntBinaryTag -> JsonPrimitive(value())
            is LongBinaryTag -> JsonPrimitive(value())
            is FloatBinaryTag -> JsonPrimitive(value())
            is DoubleBinaryTag -> JsonPrimitive(value())
            is ByteArrayBinaryTag -> JsonArray(value().map(::JsonPrimitive))
            is IntArrayBinaryTag -> JsonArray(value().map(::JsonPrimitive))
            is LongArrayBinaryTag -> JsonArray(value().map(::JsonPrimitive))
            else -> JsonPrimitive(stringify(this))
        }

    private fun extractCompound(input: String, localKey: String): String? {
        val key = Regex("[\\\"']([^\\\"']+)[\\\"']\\s*:")
            .findAll(input)
            .firstOrNull { match -> match.groupValues[1].substringAfter(':') == localKey }
            ?: return null
        val objectStart = input.indexOf('{', key.range.last + 1)
        if (objectStart < 0) return null

        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in objectStart until input.length) {
            val character = input[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (character == '\\') {
                escaped = true
                continue
            }
            if (quote != null) {
                if (character == quote) quote = null
                continue
            }
            if (character == '\"' || character == '\'') {
                quote = character
                continue
            }
            when (character) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return input.substring(objectStart, index + 1)
                }
            }
        }
        return null
    }

    /** Converts raw strings in mixed text-component lists to `{text: ...}` compounds. */
    private fun sanitizeTextComponentExtraLists(input: String): String {
        val output = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            if (input.startsWith("extra:[", index)) {
                output.append("extra:[")
                index += "extra:[".length
                val (contents, nextIndex) = sanitizeExtraArrayContents(input, index)
                output.append(contents)
                index = nextIndex
                continue
            }
            val (value, nextIndex) = copyValue(input, index)
            output.append(value)
            index = nextIndex
        }
        return output.toString()
    }

    private fun sanitizeExtraArrayContents(input: String, startIndex: Int): Pair<String, Int> {
        val elements = mutableListOf<Pair<String, Boolean>>()
        var index = startIndex
        while (index < input.length) {
            index = skipWhitespace(input, index)
            if (index < input.length && input[index] == ']') {
                index++
                break
            }
            val (element, nextIndex, isString) = parseValue(input, index)
            elements += element to isString
            index = skipWhitespace(input, nextIndex)
            if (index < input.length && input[index] == ',') index++
        }
        val mixed = elements.any { (_, isString) -> !isString }
        val contents = elements.joinToString(",") { (element, isString) ->
            if (mixed && isString) "{text:$element}" else element
        }
        return "$contents]" to index
    }

    private fun parseValue(input: String, startIndex: Int): Triple<String, Int, Boolean> {
        val index = skipWhitespace(input, startIndex)
        if (index >= input.length) return Triple("", index, false)
        return when (input[index]) {
            '\"', '\'' -> copyQuoted(input, index).let { (value, next) -> Triple(value, next, true) }
            '{' -> copyDelimited(input, index, '{', '}').let { (value, next) -> Triple(value, next, false) }
            '[' -> copyDelimited(input, index, '[', ']').let { (value, next) -> Triple(value, next, false) }
            else -> {
                var cursor = index
                while (cursor < input.length && input[cursor] != ',' && input[cursor] != ']') cursor++
                Triple(input.substring(index, cursor), cursor, false)
            }
        }
    }

    private fun copyValue(input: String, index: Int): Pair<String, Int> = when (input[index]) {
        '\"', '\'' -> copyQuoted(input, index)
        '{' -> copyDelimited(input, index, '{', '}')
        '[' -> copyDelimited(input, index, '[', ']')
        else -> input[index].toString() to index + 1
    }

    private fun copyDelimited(input: String, startIndex: Int, open: Char, close: Char): Pair<String, Int> {
        val output = StringBuilder().append(open)
        var index = startIndex + 1
        while (index < input.length) {
            if (input.startsWith("extra:[", index)) {
                output.append("extra:[")
                index += "extra:[".length
                val (contents, nextIndex) = sanitizeExtraArrayContents(input, index)
                output.append(contents)
                index = nextIndex
                continue
            }
            when (input[index]) {
                '\"', '\'' -> {
                    val (quoted, nextIndex) = copyQuoted(input, index)
                    output.append(quoted)
                    index = nextIndex
                }
                '{' -> {
                    val (compound, nextIndex) = copyDelimited(input, index, '{', '}')
                    output.append(compound)
                    index = nextIndex
                }
                '[' -> {
                    val (list, nextIndex) = copyDelimited(input, index, '[', ']')
                    output.append(list)
                    index = nextIndex
                }
                else -> {
                    val character = input[index++]
                    output.append(character)
                    if (character == close) return output.toString() to index
                }
            }
        }
        return output.toString() to index
    }

    private fun copyQuoted(input: String, startIndex: Int): Pair<String, Int> {
        val quote = input[startIndex]
        val output = StringBuilder().append(quote)
        var index = startIndex + 1
        var escaped = false
        while (index < input.length) {
            val character = input[index++]
            output.append(character)
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == quote) {
                break
            }
        }
        return output.toString() to index
    }

    private fun skipWhitespace(input: String, startIndex: Int): Int {
        var index = startIndex
        while (index < input.length && input[index].isWhitespace()) index++
        return index
    }
}
