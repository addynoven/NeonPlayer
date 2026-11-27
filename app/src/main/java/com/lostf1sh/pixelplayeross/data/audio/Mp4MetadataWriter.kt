package com.lostf1sh.pixelplayeross.data.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import timber.log.Timber

/** Metadata to embed in a saved audio file, so other players show the song and not a bare file. */
data class AudioFileTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val coverBytes: ByteArray? = null
)

/**
 * Writes iTunes-style metadata into an MP4/M4A file: title, artist, album, genre and cover art.
 *
 * The tags live in `moov/udta/meta/ilst`, which most files downloaded from YouTube do not have.
 * Adding that box makes `moov` bigger, and every chunk offset in `stco`/`co64` is an absolute
 * position in the file, so when media data follows the header those offsets have to move by the
 * same amount or the file no longer decodes. The rewrite goes to a temp file first because a
 * half-written container is unplayable.
 */
object Mp4MetadataWriter {

    private const val HEADER_BYTES = 8
    private const val MAX_MOOV_BYTES = 16 * 1024 * 1024
    private val CONTAINER_TYPES = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "meta",
        "ilst", "tref", "mvex", "moof", "traf"
    )

    /** Returns true when the file was rewritten with the tags. */
    fun write(file: File, tags: AudioFileTags): Boolean {
        if (!file.isFile || file.length() <= HEADER_BYTES) return false
        return try {
            val moov = RandomAccessFile(file, "r").use { source -> findAtom(source, "moov") }
                ?: return false
            val payloadSize = moov.size - moov.headerBytes
            if (payloadSize <= 0L || payloadSize > MAX_MOOV_BYTES) return false
            val payload = ByteArray(payloadSize.toInt())
            RandomAccessFile(file, "r").use { source ->
                source.seek(moov.start + moov.headerBytes)
                source.readFully(payload)
            }
            val mdat = RandomAccessFile(file, "r").use { source -> findAtom(source, "mdat") }
            val mediaFollowsHeader = mdat != null && mdat.start > moov.start

            val newPayload = buildMoovPayload(payload, tags)
            val newSize = HEADER_BYTES.toLong() + newPayload.size
            val delta = newSize - moov.size
            if (mediaFollowsHeader && delta != 0L) patchChunkOffsets(newPayload, delta)
            rewrite(file, moov, newPayload)
            true
        } catch (error: Exception) {
            Timber.w(error, "Could not write tags into %s", file.name)
            false
        }
    }

    private data class Atom(
        val type: String,
        val start: Long,
        val size: Long,
        val headerBytes: Int
    )

    private class Box(val type: String, val payload: ByteArray)

    private fun findAtom(source: RandomAccessFile, wanted: String): Atom? {
        var position = 0L
        val length = source.length()
        while (position + HEADER_BYTES <= length) {
            source.seek(position)
            val size32 = source.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            source.readFully(typeBytes)
            val type = String(typeBytes, Charsets.ISO_8859_1)
            var headerBytes = HEADER_BYTES
            var size = size32
            if (size32 == 1L) {
                size = source.readLong()
                headerBytes = 16
            } else if (size32 == 0L) {
                size = length - position
            }
            if (size < headerBytes) return null
            if (type == wanted) return Atom(type, position, size, headerBytes)
            position += size
        }
        return null
    }

    /** Rebuilds the moov payload with a tag box appended, keeping everything else untouched. */
    internal fun buildMoovPayload(oldPayload: ByteArray, tags: AudioFileTags): ByteArray {
        val children = splitBoxes(oldPayload)
        val udtaIndex = children.indexOfFirst { it.type == "udta" }
        val udta = buildUdta(children.getOrNull(udtaIndex)?.payload, tags)
        val output = ByteArrayOutputStream(oldPayload.size + udta.size + 64)
        children.forEachIndexed { index, child ->
            if (index == udtaIndex) output.write(atomBytes("udta", udta))
            else output.write(atomBytes(child.type, child.payload))
        }
        if (udtaIndex < 0) output.write(atomBytes("udta", udta))
        return output.toByteArray()
    }

    private fun buildUdta(existing: ByteArray?, tags: AudioFileTags): ByteArray {
        val kept = existing
            ?.let(::splitBoxes)
            ?.filter { it.type != "meta" }
            .orEmpty()
        val output = ByteArrayOutputStream()
        kept.forEach { box -> output.write(atomBytes(box.type, box.payload)) }
        output.write(atomBytes("meta", buildMeta(tags)))
        return output.toByteArray()
    }

    private fun buildMeta(tags: AudioFileTags): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0, 0, 0, 0))
        val hdlr = ByteArrayOutputStream()
        // version+flags, pre-defined, handler type, reserved, then the "appl" name.
        hdlr.write(byteArrayOf(0, 0, 0, 0))
        hdlr.write(u32(0))
        hdlr.write("mdir".toByteArray(Charsets.ISO_8859_1))
        hdlr.write(ByteArray(12))
        hdlr.write("appl".toByteArray(Charsets.ISO_8859_1))
        output.write(atomBytes("hdlr", hdlr.toByteArray()))
        output.write(atomBytes("ilst", buildIlst(tags)))
        return output.toByteArray()
    }

    private fun buildIlst(tags: AudioFileTags): ByteArray {
        val output = ByteArrayOutputStream()
        tags.title?.takeIf { it.isNotBlank() }?.let { value ->
            output.write(textItem("\u00A9nam", value))
        }
        tags.artist?.takeIf { it.isNotBlank() }?.let { value ->
            output.write(textItem("\u00A9ART", value))
            output.write(textItem("aART", value))
        }
        tags.album?.takeIf { it.isNotBlank() }?.let { value ->
            output.write(textItem("\u00A9alb", value))
        }
        tags.genre?.takeIf { it.isNotBlank() }?.let { value ->
            output.write(textItem("\u00A9gen", value))
        }
        output.write(textItem("\u00A9too", "NeonPlayer"))
        tags.coverBytes?.let { bytes ->
            val type = when {
                bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> 13
                bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> 14
                else -> 0
            }
            if (type != 0) {
                output.write(atomBytes("covr", atomBytes("data", dataPayload(type, bytes))))
            }
        }
        return output.toByteArray()
    }

    private fun textItem(name: String, value: String): ByteArray = atomBytes(
        name,
        atomBytes("data", dataPayload(1, value.toByteArray(Charsets.UTF_8)))
    )

    /** Payload of an iTunes data box: type indicator, locale, then the value. */
    private fun dataPayload(type: Int, payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(payload.size + 8)
        output.write(byteArrayOf(0, 0, 0, type.toByte()))
        output.write(byteArrayOf(0, 0, 0, 0))
        output.write(payload)
        return output.toByteArray()
    }

    internal fun patchChunkOffsets(payload: ByteArray, delta: Long) {
        walkBoxes(payload, 0, payload.size, delta)
    }

    private fun walkBoxes(bytes: ByteArray, start: Int, end: Int, delta: Long) {
        var offset = start
        while (offset + HEADER_BYTES <= end) {
            val size = readUInt32(bytes, offset)
            if (size < HEADER_BYTES || offset + size > end) return
            val type = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
            val boxEnd = offset + size.toInt()
            when {
                type == "stco" -> patchChunkTable(bytes, offset, boxEnd, wide = false, delta = delta)
                type == "co64" -> patchChunkTable(bytes, offset, boxEnd, wide = true, delta = delta)
                type in CONTAINER_TYPES -> {
                    val childrenStart = if (type == "meta") offset + 12 else offset + HEADER_BYTES
                    walkBoxes(bytes, childrenStart, boxEnd, delta)
                }
            }
            offset = boxEnd
        }
    }

    private fun patchChunkTable(bytes: ByteArray, start: Int, end: Int, wide: Boolean, delta: Long) {
        val countAt = start + 12
        if (countAt + 4 > end) return
        val count = readUInt32(bytes, countAt).toInt()
        val entryBytes = if (wide) 8 else 4
        var entry = countAt + 4
        repeat(count) {
            if (entry + entryBytes > end) return
            val old = if (wide) readUInt64(bytes, entry) else readUInt32(bytes, entry)
            val updated = (old + delta).coerceAtLeast(0L)
            if (wide) writeUInt64(bytes, entry, updated) else writeUInt32(bytes, entry, updated)
            entry += entryBytes
        }
    }

    private fun splitBoxes(bytes: ByteArray): List<Box> {
        val boxes = ArrayList<Box>()
        var offset = 0
        while (offset + HEADER_BYTES <= bytes.size) {
            val size = readUInt32(bytes, offset)
            if (size < HEADER_BYTES || offset + size > bytes.size) break
            val type = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
            val payloadStart = offset + HEADER_BYTES
            val payloadEnd = offset + size.toInt()
            boxes += Box(type, bytes.copyOfRange(payloadStart, payloadEnd))
            offset = payloadEnd
        }
        return boxes
    }

    private fun rewrite(file: File, moov: Atom, newPayload: ByteArray) {
        val temp = File(file.parentFile, file.name + ".mp4tags")
        try {
            FileOutputStream(temp).use { output ->
                copyRange(file, 0L, moov.start, output)
                output.write(atomBytes("moov", newPayload))
                copyRange(file, moov.start + moov.size, file.length(), output)
                output.flush()
                output.fd.sync()
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
            }
        } finally {
            temp.delete()
        }
    }

    private fun copyRange(file: File, from: Long, to: Long, output: FileOutputStream) {
        RandomAccessFile(file, "r").use { source ->
            source.seek(from)
            val buffer = ByteArray(256 * 1024)
            var remaining = to - from
            while (remaining > 0L) {
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private fun atomBytes(type: String, payload: ByteArray): ByteArray =
        atomBytes(type.toByteArray(Charsets.ISO_8859_1), payload)

    private fun atomBytes(type: ByteArray, payload: ByteArray): ByteArray {
        require(type.size == 4) { "MP4 box types are four bytes" }
        val output = ByteArray(HEADER_BYTES + payload.size)
        val size = output.size
        output[0] = (size ushr 24).toByte()
        output[1] = (size ushr 16).toByte()
        output[2] = (size ushr 8).toByte()
        output[3] = size.toByte()
        type.copyInto(output, destinationOffset = 4)
        payload.copyInto(output, destinationOffset = HEADER_BYTES)
        return output
    }

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private fun readUInt64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            bytes[offset + index] = (value ushr (24 - index * 8)).toByte()
        }
    }

    private fun writeUInt64(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) {
            bytes[offset + index] = (value ushr (56 - index * 8)).toByte()
        }
    }
}