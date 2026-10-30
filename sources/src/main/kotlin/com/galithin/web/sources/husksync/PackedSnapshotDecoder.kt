package com.galithin.web.sources.husksync

import com.galithin.web.sources.objectMapper
import java.nio.charset.StandardCharsets
import org.xerial.snappy.Snappy

class PackedSnapshotDecoder(
    private val supportedFormats: Set<Int> = setOf(5),
) {
    fun decode(bytes: ByteArray): DecodedSnapshot {
        val json = String(Snappy.uncompress(bytes), StandardCharsets.UTF_8)
        val root = objectMapper.readTree(json)
        val format = root.intField("formatVersion", "format_version") ?: 0
        if (format !in supportedFormats) {
            throw UnsupportedHuskSyncFormat("Unsupported HuskSync snapshot format $format")
        }
        val minecraftVersion =
            root.textField("minecraftVersion", "minecraft_version") ?: "unknown"
        val dataNode = root.path("data")
        require(dataNode.isObject) { "HuskSync snapshot has no data map" }
        val data = buildMap {
            dataNode.fields().forEach { (key, value) ->
                if (value.isTextual) put(key, value.asText())
            }
        }
        return DecodedSnapshot(format, minecraftVersion, data)
    }

    private fun com.fasterxml.jackson.databind.JsonNode.intField(vararg names: String): Int? =
        names.firstNotNullOfOrNull { name -> path(name).takeIf { it.isInt }?.asInt() }

    private fun com.fasterxml.jackson.databind.JsonNode.textField(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> path(name).takeIf { it.isTextual }?.asText() }
}
