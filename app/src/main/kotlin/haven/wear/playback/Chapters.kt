package haven.wear.playback

import java.io.File

/**
 * One navigable track inside a merged single-file album: the Herald pipeline concatenates an
 * album's tracks into one MP3 and embeds the cue points as ID3 chapters (`CHAP` + `CTOC`
 * frames), so the file stays one sealed record while readers can still jump per track.
 *
 * Media3/the platform retriever never surface these frames, so this file parses the tag by
 * hand. Everything here is fail-soft — a missing, truncated, or foreign tag yields no
 * chapters, never a crash — because chapter navigation must never break playback.
 */
data class AudioChapter(
    /** Seek target, milliseconds from the start of the file. */
    val startMs: Long,
    /** Track title from the chapter's `TIT2` subframe (element id, then a fallback). */
    val title: String,
)

/**
 * Which chapter contains [positionMs]: the latest start at or before the position.
 * Order-free (a `CTOC` order is authoritative for display but need not be time order).
 * -1 when there are no chapters, so the UI highlights nothing.
 */
fun chapterIndexAt(chapters: List<AudioChapter>, positionMs: Long): Int {
    if (chapters.isEmpty()) return -1
    var best = 0
    var bestStart = Long.MIN_VALUE
    for (i in chapters.indices) {
        val start = chapters[i].startMs
        if (start <= positionMs && start > bestStart) {
            best = i
            bestStart = start
        }
    }
    // A position before every start (pre-gap) still highlights the first chapter.
    return best
}

/**
 * Parses ID3v2.3/v2.4 chapters out of [tag] (the tag bytes, header included). Pure —
 * file IO lives in [readId3Chapters] so tests pin the byte layout without a device.
 *
 * Understands what ffmpeg writes (v2.4, plain `CHAP` + one `CTOC`, UTF-8 `TIT2`) plus the
 * v2.3 spelling and the global unsynchronisation/extension-header flags; anything else
 * (v2.2, compressed frames, garbage) parses as no chapters.
 */
fun parseId3Chapters(tag: ByteArray): List<AudioChapter> {
    if (tag.size < ID3_HEADER_SIZE) return emptyList()
    if (tag[0] != 'I'.code.toByte() || tag[1] != 'D'.code.toByte() || tag[2] != '3'.code.toByte()) {
        return emptyList()
    }
    // v2.2 has three-letter frame ids and no CHAP frame — nothing to find.
    val major = tag[3].toInt() and 0xFF
    if (major != 3 && major != 4) return emptyList()
    val flags = tag[5].toInt() and 0xFF

    val tagSize = syncsafe(tag, 6)
    var body = tag.copyOfRange(ID3_HEADER_SIZE, minOf(ID3_HEADER_SIZE + tagSize, tag.size))
    if (flags and UNSYNC_FLAG != 0) body = deunsync(body)
    if (flags and EXT_HEADER_FLAG != 0) {
        // v2.3 counts the size field itself, v2.4 counts only what follows it.
        val skip = if (major == 3) u32(body, 0) else syncsafe(body, 0) + 4
        if (skip < 0 || skip > body.size) return emptyList()
        body = body.copyOfRange(skip, body.size)
    }

    val chapters = mutableMapOf<String, ParsedChapter>()
    val tocOrders = mutableListOf<List<String>>()
    var pos = 0
    while (pos + FRAME_HEADER_SIZE <= body.size) {
        // A zero id is tag padding (writers pad to 2KB boundaries); stop, don't scan on.
        if (body[pos] == 0.toByte()) break
        val id = String(body, pos, 4, Charsets.ISO_8859_1)
        val size = if (major == 3) u32(body, pos + 4) else syncsafe(body, pos + 4)
        val frameFlags = u16(body, pos + 8)
        val dataStart = pos + FRAME_HEADER_SIZE
        // Compression/encryption bits mean the bytes aren't frames; skip, don't misparse.
        val opaque = frameFlags and (if (major == 3) 0x0080 or 0x0040 else 0x0008 or 0x0004) != 0
        if (size < 0 || dataStart + size > body.size) break
        if (!opaque && (id == "CHAP" || id == "CTOC")) {
            val data = body.copyOfRange(dataStart, dataStart + size)
            if (id == "CHAP") parseChap(data, major)?.let { chapters[it.elementId] = it }
            else parseCtoc(data)?.let { tocOrders.add(it) }
        }
        pos = dataStart + size
    }
    if (chapters.isEmpty()) return emptyList()

    val ordered = orderedChapters(chapters, tocOrders)
    return ordered.mapIndexed { index, chapter ->
        AudioChapter(
            startMs = chapter.startMs,
            title = chapter.title.ifEmpty { chapter.elementId.ifEmpty { "Chapter ${index + 1}" } },
        )
    }
}

/**
 * Reads at most the ID3 tag region of [file] and parses its chapters. The tag size comes
 * from the 10-byte header, so a 176MB album costs one small sequential read, not a load.
 * Anything unreadable, oversized, or unparseable yields no chapters.
 */
fun readId3Chapters(file: File, maxTagBytes: Int = MAX_TAG_BYTES): List<AudioChapter> {
    return runCatching {
        file.inputStream().buffered().use { stream ->
            val header = ByteArray(ID3_HEADER_SIZE)
            if (stream.read(header) < ID3_HEADER_SIZE) return@use emptyList()
            val tagSize = syncsafe(header, 6)
            if (tagSize < 0 || tagSize > maxTagBytes) return@use emptyList()
            val tag = header + ByteArray(tagSize).also { body ->
                var filled = 0
                while (filled < tagSize) {
                    val read = stream.read(body, filled, tagSize - filled)
                    if (read < 0) break
                    filled += read
                }
            }
            parseId3Chapters(tag)
        }
    }.getOrElse { emptyList() }
}

/** A `CHAP` frame before display ordering: element id, seek target, subframe title. */
private data class ParsedChapter(val elementId: String, val startMs: Long, val title: String)

/**
 * Display order: the first `CTOC` entry list wins (it is the author's order, and ffmpeg
 * writes exactly one), with any chapters the table omits appended by start time. No
 * table at all means start-time order.
 */
private fun orderedChapters(
    chapters: Map<String, ParsedChapter>,
    tocOrders: List<List<String>>,
): List<ParsedChapter> {
    val order = tocOrders.firstOrNull { it.isNotEmpty() } ?: return chapters.values.sortedBy { it.startMs }
    val listed = order.mapNotNull { chapters[it] }
    val unlisted = chapters.values.filter { it.elementId !in order }.sortedBy { it.startMs }
    return listed + unlisted
}

/**
 * `CHAP`: null-terminated element id, start/end ms, start/end offsets (usually unknown),
 * then subframes — the title is the first `TIT2`. Times are plain u32 even in v2.4.
 */
private fun parseChap(data: ByteArray, major: Int): ParsedChapter? {
    val elementId = cstr(data, 0) ?: return null
    val timesAt = elementId.second
    if (timesAt + 16 > data.size) return null
    val startMs = u32(data, timesAt).toLong() and 0xFFFF_FFFFL
    val subframes = data.copyOfRange(timesAt + 16, data.size)
    val title = firstTextSubframe(subframes, major, "TIT2") ?: ""
    return ParsedChapter(elementId.first, startMs, title)
}

/** `CTOC`: element id, one flags byte, entry count, then that many element ids. */
private fun parseCtoc(data: ByteArray): List<String>? {
    val elementId = cstr(data, 0) ?: return null
    var pos = elementId.second
    if (pos + 2 > data.size) return null
    val count = data[pos + 1].toInt() and 0xFF
    pos += 2
    val entries = mutableListOf<String>()
    repeat(count) {
        val entry = cstr(data, pos) ?: return null
        entries.add(entry.first)
        pos = entry.second
    }
    return entries
}

/** First text subframe with [wantedId] inside a `CHAP`/`CTOC` payload; null when absent. */
private fun firstTextSubframe(data: ByteArray, major: Int, wantedId: String): String? {
    var pos = 0
    while (pos + FRAME_HEADER_SIZE <= data.size) {
        if (data[pos] == 0.toByte()) break
        val id = String(data, pos, 4, Charsets.ISO_8859_1)
        val size = if (major == 3) u32(data, pos + 4) else syncsafe(data, pos + 4)
        val start = pos + FRAME_HEADER_SIZE
        if (size < 0 || start + size > data.size) break
        if (id == wantedId) return decodeText(data.copyOfRange(start, start + size))
        pos = start + size
    }
    return null
}

/**
 * ID3 text value: one encoding byte, then the string. 0 = Latin-1, 1 = UTF-16 with BOM,
 * 2 = UTF-16BE, 3 = UTF-8. Trailing nulls stripped; a BOM-less UTF-16 reads as LE, the
 * common writer mistake.
 */
private fun decodeText(data: ByteArray): String {
    if (data.isEmpty()) return ""
    val encoding = data[0].toInt() and 0xFF
    val raw = data.copyOfRange(1, data.size)
    return when (encoding) {
        1, 2 -> {
            val wide = raw.copyOfRange(0, raw.size - (raw.size % 2))
            val (bytes, charset) = when {
                encoding == 1 && wide.size >= 2 && wide[0] == 0xFF.toByte() && wide[1] == 0xFE.toByte() ->
                    wide.copyOfRange(2, wide.size) to Charsets.UTF_16LE
                encoding == 1 && wide.size >= 2 && wide[0] == 0xFE.toByte() && wide[1] == 0xFF.toByte() ->
                    wide.copyOfRange(2, wide.size) to Charsets.UTF_16BE
                else -> wide to if (encoding == 1) Charsets.UTF_16LE else Charsets.UTF_16BE
            }
            String(bytes, charset).trimEnd('\u0000')
        }
        else -> String(raw, if (encoding == 3) Charsets.UTF_8 else Charsets.ISO_8859_1).trimEnd('\u0000')
    }
}

/** Latin-1 C string at [from]: text plus the offset just past the null (null if unterminated). */
private fun cstr(data: ByteArray, from: Int): Pair<String, Int>? {
    if (from >= data.size) return null
    var end = from
    while (end < data.size && data[end] != 0.toByte()) end++
    if (end >= data.size) return null
    return String(data, from, end - from, Charsets.ISO_8859_1) to (end + 1)
}

/** Global unsynchronisation: every `0xFF 0x00` in the tag body stands for a bare `0xFF`. */
private fun deunsync(body: ByteArray): ByteArray {
    val out = ByteArray(body.size)
    var read = 0
    var written = 0
    while (read < body.size) {
        val byte = body[read++]
        out[written++] = byte
        if (byte == 0xFF.toByte() && read < body.size && body[read] == 0.toByte()) read++
    }
    return out.copyOf(written)
}

private fun u16(data: ByteArray, at: Int): Int {
    if (at < 0 || at + 2 > data.size) return 0
    return ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
}

private fun u32(data: ByteArray, at: Int): Int {
    if (at < 0 || at + 4 > data.size) return 0
    var value = 0
    for (i in 0 until 4) value = (value shl 8) or (data[at + i].toInt() and 0xFF)
    return value
}

/** Syncsafe integer: four bytes, seven bits each — the tag header and v2.4 frame sizes. */
private fun syncsafe(data: ByteArray, at: Int): Int {
    if (at < 0 || at + 4 > data.size) return 0
    var value = 0
    for (i in 0 until 4) value = (value shl 7) or (data[at + i].toInt() and 0x7F)
    return value
}

private const val ID3_HEADER_SIZE = 10
private const val FRAME_HEADER_SIZE = 10
private const val UNSYNC_FLAG = 0x80
private const val EXT_HEADER_FLAG = 0x40

/**
 * Largest tag region [readId3Chapters] will pull into memory. Cover art lives in the same
 * tag, so this comfortably exceeds any real chapters-plus-picture header; past it the tag
 * is corrupt, not an album.
 */
private const val MAX_TAG_BYTES = 16 * 1024 * 1024
