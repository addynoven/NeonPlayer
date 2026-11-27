package com.lostf1sh.pixelplayeross.data.audio

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Mp4MetadataWriterTest {

    @Test
    fun `tags are added and chunk offsets move with the header`(@TempDir dir: File) {
        val file = dir.resolve("track.m4a")
        val original = mp4(moovFirst = true)
        file.writeBytes(original)
        val originalChunks = chunkOffsets(original)

        val written = Mp4MetadataWriter.write(
            file,
            AudioFileTags(
                title = "Night Drive",
                artist = "Neon Artist",
                album = "Neon Album",
                coverBytes = jpegHeader()
            )
        )

        val bytes = file.readBytes()
        assertThat(written).isTrue()
        assertThat(bytes.size).isGreaterThan(original.size)
        val delta = (bytes.size - original.size).toLong()
        assertThat(chunkOffsets(bytes)).isEqualTo(originalChunks.map { it + delta })
        assertThat(String(bytes, Charsets.ISO_8859_1)).contains("\u00A9nam")
        assertThat(String(bytes, Charsets.ISO_8859_1)).contains("Night Drive")
        assertThat(String(bytes, Charsets.ISO_8859_1)).contains("Neon Artist")
        assertThat(String(bytes, Charsets.ISO_8859_1)).contains("Neon Album")
        assertThat(String(bytes, Charsets.ISO_8859_1)).contains("covr")
        // Each item must hold a nested data box, otherwise players read an empty tag.
        assertItemHoldsDataBox(bytes, "\u00A9nam", expectedType = 1)
        assertItemHoldsDataBox(bytes, "covr", expectedType = 13)
        // Media data is copied through untouched, so the file still decodes.
        assertThat(bytes.takeLast(MDAT_BYTES)).isEqualTo(original.takeLast(MDAT_BYTES))
    }

    @Test
    fun `media data before the header keeps its offsets`(@TempDir dir: File) {
        val file = dir.resolve("streamed.m4a")
        val original = mp4(moovFirst = false)
        file.writeBytes(original)
        val originalChunks = chunkOffsets(original)

        Mp4MetadataWriter.write(file, AudioFileTags(title = "Late header"))

        val bytes = file.readBytes()
        assertThat(chunkOffsets(bytes)).isEqualTo(originalChunks)
        assertThat(String(bytes, Charsets.ISO_8859_1)).contains("Late header")
    }

    @Test
    fun `a file without a moov box is left alone`(@TempDir dir: File) {
        val file = dir.resolve("broken.m4a")
        val original = box("ftyp", "M4A ".toByteArray() + u32(0) + "isom".toByteArray())
        file.writeBytes(original)

        val written = Mp4MetadataWriter.write(file, AudioFileTags(title = "x"))

        assertThat(written).isFalse()
        assertThat(file.readBytes()).isEqualTo(original)
    }

    private fun mp4(moovFirst: Boolean): ByteArray {
        val ftyp = box("ftyp", "M4A ".toByteArray() + u32(0) + "isom".toByteArray() + "M4A ".toByteArray())
        val stco = box(
            "stco",
            u32(0) + u32(2) + u32(CHUNK_ONE) + u32(CHUNK_TWO)
        )
        val stbl = box("stbl", stco)
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        val moov = box("moov", trak)
        val mdat = box("mdat", ByteArray(MDAT_BYTES) { index -> index.toByte() })
        return if (moovFirst) ftyp + moov + mdat else ftyp + mdat + moov
    }

    private fun assertItemHoldsDataBox(bytes: ByteArray, item: String, expectedType: Int) {
        val inner = indexOfType(bytes, item) + 8
        assertThat(String(bytes, inner + 4, 4, Charsets.ISO_8859_1)).isEqualTo("data")
        assertThat(bytes[inner + 11].toInt()).isEqualTo(expectedType)
    }

    private fun chunkOffsets(bytes: ByteArray): List<Long> {
        val start = indexOfType(bytes, "stco")
        val count = readU32(bytes, start + 12).toInt()
        return (0 until count).map { index -> readU32(bytes, start + 16 + index * 4) }
    }

    private fun indexOfType(bytes: ByteArray, type: String): Int {
        val needle = type.toByteArray(Charsets.ISO_8859_1)
        for (offset in 0..bytes.size - 8) {
            var match = true
            for (index in 0 until 4) {
                if (bytes[offset + 4 + index] != needle[index]) {
                    match = false
                    break
                }
            }
            if (match && readU32(bytes, offset) >= 8) return offset
        }
        throw AssertionError("No $type box")
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return u32(size) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private fun jpegHeader(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(16)

    private companion object {
        const val MDAT_BYTES = 64
        const val CHUNK_ONE = 0x1000
        const val CHUNK_TWO = 0x2000
    }
}