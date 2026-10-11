package haven.mobile.core.crypto

import haven.mobile.core.domain.error.HavenError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream

/**
 * Strips a CARv1 container from a byte stream, passing the block data through.
 *
 * Retrieval (`/piece/`) serves the stored CAR file, while [HavenCipher] expects the raw
 * chunked ciphertext the uploader encrypted. Without this step the CAR header is mistaken
 * for the base IV and every chunk fails its GCM tag check (`bad_decrypt`).
 *
 * Streaming and bounded: raw-leaf payloads emit as they arrive (only the frame
 * length and CID stage, both tiny), and only dag-pb directory nodes ever buffer
 * (roots are kilobytes; buffered dag-pb is capped). A single-block multi-GB
 * piece therefore stages in constant memory and decrypts progressively instead
 * of pinning the progress bar at 0% until the whole file lands.
 *
 * Scope is deliberately narrow — the IPIP-499 UnixFS shape and nothing else:
 * - Non-CAR input (legacy raw ciphertext) passes through byte-identical.
 * - Raw-leaf blocks (`0x55`) stream through in arrival order.
 * - Exactly one dag-pb block (`0x70`) may follow: it must be the CAR header's root,
 *   and its links must name the raw blocks in the order they arrived. Then the
 *   stream was a faithful file and the bytes stand.
 * - Anything else fails LOUD instead of decrypting frame headers as ciphertext:
 *   a second dag-pb node (nested/HAMT sharding), a root whose links disagree with
 *   arrival order, multiple raw blocks with no root to check them against, or an
 *   unknown codec. Truncated/ambiguous prefixes fail OPEN (pass through) so the
 *   decrypt layer — which already fails closed with framing/tag errors — stays the
 *   single judge of validity.
 *
 * @param onProgress reports cumulative `(carBytesReceived, payloadBytesEmitted)`
 * after each processed chunk so the UI can distinguish a slow fetch (received
 * climbing) from a wedged pipeline (received climbing, emitted flat).
 */
fun Flow<ByteArray>.stripCarContainer(
    onProgress: ((received: Long, emitted: Long) -> Unit)? = null,
): Flow<ByteArray> = flow {
    val pending = ByteArrayOutputStream()
    var phase: Phase = Phase.PREFIX
    var headerRoots: List<ByteArray> = emptyList()
    // Current frame assembly in three stages: length varint, CID, then payload.
    // Only the first two ever stage bytes; raw payloads stream straight through.
    var frameLen: Long? = null
    var frameCid: ByteArray? = null
    var frameCodec: Long? = null
    var payloadRemaining: Long = 0
    var streamingRaw = false
    val stage = ByteArrayOutputStream()
    val rawCids = mutableListOf<ByteArray>()
    val dagPb = mutableMapOf<String, ByteArray>()
    var dagPbBuffered = 0L
    var received = 0L
    var emitted = 0L

    suspend fun emitBytes(bytes: ByteArray) {
        if (bytes.isNotEmpty()) {
            emitted += bytes.size
            emit(bytes)
        }
    }

    /** Feed bytes through the frame assembler: lengths, CIDs, and payloads may all split. */
    suspend fun consume(bytes: ByteArray) {
        var rest = bytes
        while (rest.isNotEmpty()) {
            if (frameLen == null) {
                // A length prefix may already be staged from the previous chunk.
                val all = stage.toByteArray() + rest
                val parsed = readVarint(all, 0)
                if (parsed == null) {
                    // Still split: stage everything and wait for the next chunk.
                    if (all.size > MAX_PREFIX_BYTES) {
                        throw HavenError.PlaybackDecryptFailed("CAR frame has no parsable length")
                    }
                    stage.reset()
                    stage.write(all)
                    return
                }
                val (len, next) = parsed
                if (len <= 0) throw HavenError.PlaybackDecryptFailed("CAR frame has no parsable length")
                frameLen = len
                stage.reset()
                rest = all.copyOfRange(next, all.size)
                continue
            }
            if (frameCid == null) {
                // Stage until the CID parses (CIDs are tens of bytes, never megabytes).
                val all = stage.toByteArray() + rest
                val parsed = parseCid(all, 0)
                if (parsed == null) {
                    if (all.size > MAX_PREFIX_BYTES) {
                        throw HavenError.PlaybackDecryptFailed("CAR block has no parsable CIDv1")
                    }
                    stage.reset()
                    stage.write(all)
                    return
                }
                val (codec, cidLen) = parsed
                frameCid = all.copyOfRange(0, cidLen)
                frameCodec = codec
                payloadRemaining = frameLen!! - cidLen
                if (payloadRemaining < 0) {
                    throw HavenError.PlaybackDecryptFailed("CAR frame shorter than its CID")
                }
                when (codec) {
                    RAW_CODEC -> {
                        rawCids += frameCid!!
                        streamingRaw = true
                    }
                    DAG_PB_CODEC -> {
                        streamingRaw = false
                    }
                    else -> throw HavenError.PlaybackDecryptFailed(
                        "Unsupported CAR block codec 0x${codec.toString(16)}: only raw leaves and the dag-pb root are supported",
                    )
                }
                stage.reset()
                rest = all.copyOfRange(cidLen, all.size)
                continue
            }
            val take = minOf(payloadRemaining, rest.size.toLong()).toInt()
            if (streamingRaw) {
                emitBytes(rest.copyOfRange(0, take))
            } else {
                if (dagPbBuffered + stage.size() + take > MAX_DAG_PB_BYTES) {
                    throw HavenError.PlaybackDecryptFailed("CAR directory data exceeds the reader's cap")
                }
                stage.write(rest, 0, take)
            }
            rest = rest.copyOfRange(take, rest.size)
            payloadRemaining -= take
            if (payloadRemaining == 0L) {
                if (!streamingRaw) {
                    dagPbBuffered += stage.size()
                    dagPb[frameCid!!.toHexKey()] = stage.toByteArray()
                }
                frameLen = null
                frameCid = null
                frameCodec = null
                streamingRaw = false
                stage.reset()
            }
        }
    }

    collect { incoming ->
        if (incoming.isEmpty()) return@collect
        received += incoming.size
        when (phase) {
            Phase.RAW -> emitBytes(incoming)
            Phase.PREFIX -> {
                pending.write(incoming)
                when (val decision = decide(pending.toByteArray())) {
                    is Decision.NotCar -> {
                        phase = Phase.RAW
                        emitBytes(pending.toByteArray())
                        pending.reset()
                    }
                    is Decision.Car -> {
                        phase = Phase.FRAMES
                        headerRoots = parseHeaderRoots(decision.header) ?: emptyList()
                        // Seed the assembler with everything from the first frame's
                        // CID onward. The frame length is already known, so consume()
                        // picks up at the CID stage and streams any payload in the seed.
                        val rest = pending.toByteArray().copyOfRange(decision.frameCidOffset, pending.size())
                        pending.reset()
                        frameLen = decision.firstFrameLen
                        consume(rest)
                    }
                    Decision.Undecided -> {
                        if (pending.size() > MAX_PREFIX_BYTES) {
                            phase = Phase.RAW
                            emitBytes(pending.toByteArray())
                            pending.reset()
                        }
                    }
                }
            }
            Phase.FRAMES -> consume(incoming)
        }
        onProgress?.invoke(received, emitted)
    }

    if (phase == Phase.PREFIX) {
        // Stream ended with an undecided prefix: fail open so the decrypt layer judges it.
        emitBytes(pending.toByteArray())
        onProgress?.invoke(received, emitted)
        return@flow
    }
    if (phase == Phase.FRAMES && (stage.size() > 0 || frameLen != null)) {
        throw HavenError.PlaybackDecryptFailed("Truncated CAR container")
    }
    verifyRoot(rawCids, dagPb, headerRoots)
}

/**
 * End-of-stream check: without a root nothing ordered multi-block data, with one
 * its links must name exactly the raw blocks we already emitted, in order.
 */
private fun verifyRoot(
    rawCids: List<ByteArray>,
    dagPb: Map<String, ByteArray>,
    headerRoots: List<ByteArray>,
) {
    if (dagPb.isEmpty()) {
        if (rawCids.size > 1) {
            throw HavenError.PlaybackDecryptFailed(
                "Multi-block CAR container with no root to verify block order: only single-block pieces are supported",
            )
        }
        return
    }
    if (dagPb.size != 1 || headerRoots.size != 1) {
        throw HavenError.PlaybackDecryptFailed(
            "Nested CAR container: only flat raw-leaf pieces are supported",
        )
    }
    val (rootCidKey, rootData) = dagPb.entries.single()
    if (rootCidKey != headerRoots.single().toHexKey()) {
        throw HavenError.PlaybackDecryptFailed(
            "Nested CAR container: only flat raw-leaf pieces are supported",
        )
    }
    val links = parseDagPbLinks(rootData)
    val expected = links.map { leafCidFor(it).toHexKey() }
    if (expected != rawCids.map { it.toHexKey() }) {
        throw HavenError.PlaybackDecryptFailed(
            "CAR block order does not match the root links: refusing to reassemble",
        )
    }
}

private enum class Phase { PREFIX, RAW, FRAMES }

/** Cap on framing bytes ever buffered while deciding; block payloads never buffer. */
private const val MAX_PREFIX_BYTES = 4096

/** Cap on buffered dag-pb directory data (roots are kilobytes). */
private const val MAX_DAG_PB_BYTES = 4 * 1024 * 1024L

private const val RAW_CODEC = 0x55L
private const val DAG_PB_CODEC = 0x70L

private fun ByteArray.toHexKey(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private sealed interface Decision {
    data object Undecided : Decision
    data object NotCar : Decision
    /**
     * Container confirmed: [header] is the dag-cbor header, the first frame's CID
     * starts at [frameCidOffset], and the first frame (CID + data) spans [firstFrameLen].
     */
    data class Car(val header: ByteArray, val frameCidOffset: Int, val firstFrameLen: Long) : Decision
}

/** Unsigned LEB128; null when the bytes so far do not terminate one. */
private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int>? {
    var value = 0L
    var shift = 0
    var pos = offset
    while (pos < data.size && pos - offset < 10) {
        val b = data[pos].toInt() and 0xFF
        value = value or ((b and 0x7F).toLong() shl shift)
        pos++
        if (b and 0x80 == 0) return value to pos
        shift += 7
    }
    return null
}

private val VERSION_PATTERN = byteArrayOf(0x67) + "version".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01)

private fun containsVersionPattern(header: ByteArray): Boolean {
    if (header.size < VERSION_PATTERN.size) return false
    outer@ for (start in 0..header.size - VERSION_PATTERN.size) {
        for (i in VERSION_PATTERN.indices) {
            if (header[start + i] != VERSION_PATTERN[i]) continue@outer
        }
        return true
    }
    return false
}

/** Parse a CIDv1 prefix; returns (codec, total CID length) or null when incomplete/invalid. */
private fun parseCid(data: ByteArray, offset: Int): Pair<Long, Int>? {
    val (version, p1) = readVarint(data, offset) ?: return null
    if (version != 1L) return null
    val (codec, p2) = readVarint(data, p1) ?: return null
    val (_, p3) = readVarint(data, p2) ?: return null // multihash code
    val (digestLen, p4) = readVarint(data, p3) ?: return null
    if (digestLen <= 0 || digestLen > 128) return null
    if (data.size - p4 < digestLen) return null
    return codec to ((p4 - offset) + digestLen.toInt())
}

/** Minimal dag-cbor header parse: returns the roots byte-strings, or null. */
private fun parseHeaderRoots(header: ByteArray): List<ByteArray>? {
    if (header.isEmpty()) return null
    val first = header[0].toInt() and 0xFF
    if (first < 0xA1 || first > 0xBF) return null
    var pos = 1
    val endPairs = first - 0xA0
    val roots = mutableListOf<ByteArray>()
    var sawVersion = false
    repeat(endPairs) {
        if (pos >= header.size) return null
        val keyHead = header[pos++].toInt() and 0xFF
        if (keyHead < 0x60 || keyHead > 0x7B) return null
        val keyLen = keyHead - 0x60
        if (pos + keyLen > header.size) return null
        val key = header.copyOfRange(pos, pos + keyLen).toString(Charsets.US_ASCII)
        pos += keyLen
        if (pos >= header.size) return null
        when (key) {
            "version" -> {
                val (v, next) = readCborUint(header, pos) ?: return null
                if (v != 1L) return null
                sawVersion = true
                pos = next
            }
            "roots" -> {
                val arrHead = header[pos++].toInt() and 0xFF
                if (arrHead < 0x80 || arrHead > 0x9F) return null
                repeat(arrHead - 0x80) {
                    val (cid, next) = readCborBytes(header, pos) ?: return null
                    roots += cid
                    pos = next
                }
            }
            // Tolerated but unused (e.g. CARv1 `characteristics`).
            else -> pos = skipCbor(header, pos) ?: return null
        }
    }
    return if (sawVersion) roots else null
}

/** Skip one CBOR value at [pos]; returns the next position or null. */
private fun skipCbor(data: ByteArray, pos: Int): Int? {
    if (pos >= data.size) return null
    val head = data[pos].toInt() and 0xFF
    val major = head ushr 5
    val info = head and 0x1F
    fun argLen(p: Int): Pair<Long, Int>? = when {
        info <= 23 -> info.toLong() to p
        info == 24 -> if (p < data.size) (data[p].toInt() and 0xFF).toLong() to p + 1 else null
        info == 25 -> if (p + 1 < data.size) (((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)).toLong() to p + 2 else null
        info == 26 -> if (p + 3 < data.size) {
            var v = 0L
            for (i in 0..3) v = (v shl 8) or (data[p + i].toInt() and 0xFF).toLong()
            v to p + 4
        } else null
        else -> null
    }
    return when (major) {
        0, 1 -> argLen(pos + 1)?.second
        2, 3 -> argLen(pos + 1)?.let { (n, next) ->
            val end = (next + n).toInt()
            if (end <= data.size) end else null
        }
        4, 5 -> argLen(pos + 1)?.let { (n, next) ->
            var p = next
            repeat(n.toInt() * (if (major == 5) 2 else 1)) { p = skipCbor(data, p) ?: return null }
            p
        }
        6 -> argLen(pos + 1)?.let { (_, next) -> skipCbor(data, next) }
        7 -> when (info) {
            20, 21, 22, 23 -> pos + 1
            25 -> if (pos + 2 < data.size) pos + 3 else null
            26 -> if (pos + 4 < data.size) pos + 5 else null
            27 -> if (pos + 8 < data.size) pos + 9 else null
            else -> null
        }
        else -> null
    }
}

/**
 * Decodes a CBOR head's argument (length/count/value) at [pos]: inline and
 * one-, two-, four- and eight-byte forms. Returns (argument, next position).
 */
private fun readCborArg(data: ByteArray, pos: Int): Pair<Long, Int>? {
    if (pos >= data.size) return null
    return when (val info = data[pos].toInt() and 0x1F) {
        in 0..23 -> info.toLong() to pos + 1
        24 -> if (pos + 1 < data.size) (data[pos + 1].toInt() and 0xFF).toLong() to pos + 2 else null
        25 -> if (pos + 2 < data.size) {
            (((data[pos + 1].toInt() and 0xFF) shl 8) or (data[pos + 2].toInt() and 0xFF)).toLong() to pos + 3
        } else null
        26 -> if (pos + 4 < data.size) {
            var v = 0L
            for (i in 1..4) v = (v shl 8) or (data[pos + i].toInt() and 0xFF).toLong()
            v to pos + 5
        } else null
        27 -> if (pos + 8 < data.size) {
            var v = 0L
            for (i in 1..8) v = (v shl 8) or (data[pos + i].toInt() and 0xFF).toLong()
            v to pos + 9
        } else null
        else -> null
    }
}

/** Unsigned CBOR int (major type 0) at [pos]. */
private fun readCborUint(data: ByteArray, pos: Int): Pair<Long, Int>? {
    if (pos >= data.size || data[pos].toInt() ushr 5 != 0) return null
    return readCborArg(data, pos)
}

/** Definite-length CBOR byte string (major type 2) at [pos]; returns its bytes. */
private fun readCborBytes(data: ByteArray, pos: Int): Pair<ByteArray, Int>? {
    if (pos >= data.size || data[pos].toInt() ushr 5 != 2) return null
    val (len, next) = readCborArg(data, pos) ?: return null
    if (len < 0 || len > MAX_PREFIX_BYTES || next + len > data.size) return null
    return data.copyOfRange(next, (next + len).toInt()) to (next + len).toInt()
}

private fun decide(prefix: ByteArray): Decision {
    val (headerLen, headerStart) = readVarint(prefix, 0) ?: return Decision.Undecided
    if (headerLen <= 0 || headerLen > MAX_PREFIX_BYTES) return Decision.NotCar
    val headerLenInt = headerLen.toInt()
    if (prefix.size - headerStart < headerLenInt) return Decision.Undecided
    val header = prefix.copyOfRange(headerStart, headerStart + headerLenInt)
    if (header.isEmpty() || header[0].toInt() and 0xFF !in 0xA1..0xBF) return Decision.NotCar
    if (!containsVersionPattern(header)) return Decision.NotCar
    var pos = headerStart + headerLenInt
    val (frameLen, frameDataStart) = readVarint(prefix, pos) ?: return Decision.Undecided
    if (frameLen <= 0) return Decision.NotCar
    pos = frameDataStart
    // Need the full CID before the first block's data starts.
    parseCid(prefix, pos) ?: run {
        // CID might be split across network chunks — wait for more unless unreasonable.
        return if (prefix.size - pos > MAX_PREFIX_BYTES) Decision.NotCar else Decision.Undecided
    }
    // Commit on header + CID alone. The first frame may be megabytes (a single-block piece
    // is one frame), and the assembler already resumes split frames across chunks — demanding
    // the whole frame here would blow past the prefix cap first, misread the container as raw
    // ciphertext, and fail every chunk's tag check downstream. A confident-but-wrong commit
    // still fails closed: the assembler, the root check, and the cipher each reject garbage.
    return Decision.Car(header, pos, frameLen)
}

/** Extract the link multihashes of a dag-pb node, in order. */
private fun parseDagPbLinks(node: ByteArray): List<ByteArray> {
    val out = mutableListOf<ByteArray>()
    var pos = 0
    while (pos < node.size) {
        val tag = node[pos++].toInt() and 0xFF
        val field = tag ushr 3
        val wire = tag and 0x07
        when (wire) {
            0 -> { while (pos < node.size && node[pos].toInt() and 0x80 != 0) pos++; pos++ }
            1 -> pos += 8
            5 -> pos += 4
            2 -> {
                val (len, next) = readVarint(node, pos) ?: return out
                val start = next
                val end = (start + len).toInt().coerceAtMost(node.size)
                if (field == 2) {
                    // A PBLink: field 1 holds the linked multihash.
                    var q = start
                    while (q < end) {
                        val ltag = node[q++].toInt() and 0xFF
                        val lfield = ltag ushr 3
                        val lwire = ltag and 0x07
                        if (lwire != 2) {
                            q = skipProtoField(node, q - 1, end) ?: return out
                            continue
                        }
                        val (llen, lnext) = readVarint(node, q) ?: return out
                        val lstart = lnext
                        val lend = (lstart + llen).toInt().coerceAtMost(end)
                        if (lfield == 1) out += node.copyOfRange(lstart, lend)
                        q = lend
                    }
                }
                pos = end
            }
            else -> return out
        }
        if (pos > node.size) return out
    }
    return out
}

private fun skipProtoField(node: ByteArray, tagPos: Int, end: Int): Int? {
    val tag = node[tagPos].toInt() and 0xFF
    var pos = tagPos + 1
    return when (tag and 0x07) {
        0 -> { while (pos < end && node[pos].toInt() and 0x80 != 0) pos++; if (pos < end) pos + 1 else null }
        1 -> if (pos + 8 <= end) pos + 8 else null
        5 -> if (pos + 4 <= end) pos + 4 else null
        2 -> {
            val (len, next) = readVarint(node, pos) ?: return null
            val fin = (next + len).toInt()
            if (fin <= end) fin else null
        }
        else -> null
    }
}

/** Rebuild the raw-leaf CID bytes for a link multihash (CIDv1 + raw codec). */
private fun leafCidFor(multihash: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(0x01)
    out.write(0x55)
    out.write(multihash, 0, multihash.size)
    return out.toByteArray()
}
