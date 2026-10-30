package com.galithin.web.sources.husksync

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.xerial.snappy.Snappy

class PackedSnapshotDecoderTest {
    @Test
    fun `decodes HuskSync v4 packed format 5 payload`() {
        val packed = Snappy.compress(
            """{"formatVersion":5,"minecraftVersion":"1.21.11","data":{"inventory":"{items:{size:41,items:[]}}","persistent_data":"{}"}}"""
                .toByteArray(StandardCharsets.UTF_8)
        )

        val decoded = PackedSnapshotDecoder().decode(packed)

        assertEquals(5, decoded.formatVersion)
        assertEquals("1.21.11", decoded.minecraftVersion)
        assertEquals("{}", decoded.data["persistent_data"])
    }

    @Test
    fun `rejects unknown formats without publishing a partial profile`() {
        val packed = Snappy.compress(
            """{"formatVersion":999,"minecraftVersion":"future","data":{}}"""
                .toByteArray(StandardCharsets.UTF_8)
        )

        assertFailsWith<UnsupportedHuskSyncFormat> { PackedSnapshotDecoder().decode(packed) }
    }
}
