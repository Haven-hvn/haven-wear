package haven.mobile.core.crypto

import haven.mobile.core.domain.error.HavenError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM for Haven content.
 *
 * Two entry points, and the difference matters:
 *
 *  - [decrypt] takes a whole buffer. Fine for key material and small payloads.
 *  - [decryptStream] is a *pipeline*: it consumes network/cache chunks as they arrive, decrypts each
 *    encryption chunk the moment its last byte lands, and emits plaintext downstream. Peak memory is
 *    one encryption chunk plus one partial — not the file. This is what makes a 2GB video openable
 *    on a 2GB device.
 */
interface HavenCipher {
    suspend fun decrypt(key: ByteArray, ciphertext: ByteArray, aad: ByteArray?): Result<ByteArray>

    /**
     * Incremental decrypt. Emissions are plaintext runs in order; a consumer can write each one
     * straight to a sink and never hold the whole payload.
     *
     * Chunk boundaries in [ciphertext] are irrelevant — the framing is re-assembled internally, so a
     * 256KiB cache read and a 1MiB encryption chunk interleave correctly.
     */
    fun decryptStream(
        key: ByteArray,
        ciphertext: Flow<ByteArray>,
        aad: ByteArray?,
    ): Flow<ByteArray>
}

/**
 * Wire format, produced by `haven-cli`'s streaming encryptor:
 *
 * ```
 * ┌────────────┬──────────────────────────────────────────────────────┐
 * │ base IV    │ chunk*                                               │
 * │ 12 bytes   │ ┌───────────┬───────────┬────────────────────────┐   │
 * │            │ │ index u32 │ length u32│ ciphertext + GCM tag   │…  │
 * │            │ │  LE       │  LE       │ `length` bytes         │   │
 * │            │ └───────────┴───────────┴────────────────────────┘   │
 * └────────────┴──────────────────────────────────────────────────────┘
 * ```
 *
 * Per-chunk IV = base IV with the big-endian chunk index XORed into bytes 4..12, so every chunk gets
 * a distinct nonce under one key, and chunks stay independently decryptable (which is what allows
 * seeking without re-reading from the start).
 *
 * Legacy payloads are a bare `IV || ciphertext+tag` with no chunk framing; both paths are supported.
 */
class HavenCipherImpl @javax.inject.Inject constructor() : HavenCipher {

    companion object {
        private const val BASE_IV_SIZE = 12
        private const val CHUNK_INDEX_SIZE = 4
        private const val CHUNK_LENGTH_SIZE = 4
        private const val CHUNK_HEADER_SIZE = CHUNK_INDEX_SIZE + CHUNK_LENGTH_SIZE
        private const val GCM_TAG_SIZE = 16
        private const val GCM_TAG_BITS = 128

        /** Sanity bound on a declared chunk length: a corrupt header must not become a 2GB alloc. */
        private const val MAX_CHUNK_SIZE = 64 * 1024 * 1024

        /**
         * Ceiling for the legacy whole-buffer path only. Legacy payloads were produced by a
         * non-streaming encryptor and are small; anything larger arriving in that shape is a
         * malformed stream, and buffering it would be the memory bug this class exists to avoid.
         */
        private const val MAX_LEGACY_BYTES = 32 * 1024 * 1024
    }

    override suspend fun decrypt(key: ByteArray, ciphertext: ByteArray, aad: ByteArray?): Result<ByteArray> {
        return try {
            if (isChunkedFormat(ciphertext)) {
                Result.success(decryptChunkedBuffer(key, ciphertext, aad))
            } else {
                Result.success(decryptLegacy(key, ciphertext, aad))
            }
        } catch (e: HavenError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(HavenError.PlaybackDecryptFailed(e.message ?: "Decrypt failed", e))
        }
    }

    override fun decryptStream(
        key: ByteArray,
        ciphertext: Flow<ByteArray>,
        aad: ByteArray?,
    ): Flow<ByteArray> = flow {
        val buffer = FrameBuffer()
        var baseIv: ByteArray? = null
        var framing = Framing.UNDECIDED
        var expectedIndex = 0

        ciphertext.collect { incoming ->
            if (incoming.isEmpty()) return@collect
            buffer.append(incoming)

            if (baseIv == null) {
                if (buffer.available < BASE_IV_SIZE) return@collect
                baseIv = buffer.consume(BASE_IV_SIZE)
            }

            if (framing == Framing.UNDECIDED) {
                if (buffer.available < CHUNK_HEADER_SIZE) return@collect
                framing = detectFraming(buffer)
            }

            if (framing != Framing.CHUNKED) {
                // Legacy: nothing to emit until the stream ends, so just guard the buffer.
                if (buffer.available > MAX_LEGACY_BYTES) {
                    throw HavenError.PlaybackDecryptFailed(
                        "Unframed payload exceeded ${MAX_LEGACY_BYTES / (1024 * 1024)}MB — refusing to buffer",
                    )
                }
                return@collect
            }

            // Drain every chunk that has fully arrived. The loop is what turns an arbitrary read
            // size into aligned decrypt work.
            while (true) {
                if (buffer.available < CHUNK_HEADER_SIZE) break
                val index = buffer.peekIntLe(0)
                val length = buffer.peekIntLe(CHUNK_INDEX_SIZE)
                if (length <= GCM_TAG_SIZE || length > MAX_CHUNK_SIZE) {
                    throw HavenError.PlaybackDecryptFailed("Chunk $index declares an invalid length ($length)")
                }
                if (buffer.available < CHUNK_HEADER_SIZE + length) break
                if (index != expectedIndex) {
                    // Out-of-order framing means the stream is corrupt or spliced; a wrong IV would
                    // otherwise surface as a confusing tag-mismatch.
                    throw HavenError.PlaybackDecryptFailed("Chunk order mismatch: expected $expectedIndex, got $index")
                }

                buffer.skip(CHUNK_HEADER_SIZE)
                val encrypted = buffer.consume(length)
                val iv = deriveChunkIv(requireNotNull(baseIv), index)
                emit(aesGcmDecrypt(key, iv, encrypted, aad))
                expectedIndex++
            }
        }

        // Stream finished. Anything left over is either a legacy body or a truncated chunk.
        when (framing) {
            Framing.CHUNKED -> {
                if (buffer.available > 0) {
                    throw HavenError.PlaybackDecryptFailed(
                        "Stream ended mid-chunk with ${buffer.available} bytes left",
                    )
                }
                if (expectedIndex == 0) {
                    throw HavenError.PlaybackDecryptFailed("No chunks in stream")
                }
            }

            Framing.LEGACY -> {
                val iv = baseIv ?: throw HavenError.PlaybackDecryptFailed("Stream ended before the IV")
                val body = buffer.consume(buffer.available)
                if (body.isEmpty()) throw HavenError.PlaybackDecryptFailed("Stream ended before any ciphertext")
                emit(aesGcmDecrypt(key, iv, body, aad))
            }

            Framing.UNDECIDED -> throw HavenError.PlaybackDecryptFailed(
                "Stream ended before a readable header",
            )
        }
    }.flowOn(Dispatchers.Default)

    private enum class Framing { UNDECIDED, CHUNKED, LEGACY }

    /**
     * Framing is decided from the first would-be header, which is all a stream can see.
     *
     * A chunked payload always starts with index 0 and a length above the GCM tag size; a legacy
     * payload's first eight ciphertext bytes are effectively random, so agreeing with both
     * constraints at once is vanishingly unlikely.
     */
    private fun detectFraming(buffer: FrameBuffer): Framing {
        val index = buffer.peekIntLe(0)
        val length = buffer.peekIntLe(CHUNK_INDEX_SIZE)
        return if (index == 0 && length > GCM_TAG_SIZE && length <= MAX_CHUNK_SIZE) {
            Framing.CHUNKED
        } else {
            Framing.LEGACY
        }
    }

    /** Whole-buffer variant of the same check, which can also verify the length against the total. */
    private fun isChunkedFormat(data: ByteArray): Boolean {
        if (data.size < BASE_IV_SIZE + CHUNK_HEADER_SIZE) return false
        val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val index = header.getInt(BASE_IV_SIZE)
        val length = header.getInt(BASE_IV_SIZE + CHUNK_INDEX_SIZE)
        return index == 0 &&
            length > GCM_TAG_SIZE &&
            length <= MAX_CHUNK_SIZE &&
            BASE_IV_SIZE + CHUNK_HEADER_SIZE + length <= data.size
    }

    private fun decryptChunkedBuffer(key: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val baseIv = data.copyOfRange(0, BASE_IV_SIZE)
        var offset = BASE_IV_SIZE
        var expectedIndex = 0
        val parts = ArrayList<ByteArray>()
        var total = 0

        while (offset < data.size) {
            if (offset + CHUNK_HEADER_SIZE > data.size) {
                throw HavenError.PlaybackDecryptFailed("Truncated chunk header at $offset")
            }
            val header = ByteBuffer.wrap(data, offset, CHUNK_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val index = header.int
            val length = header.int
            if (length <= GCM_TAG_SIZE || length > MAX_CHUNK_SIZE) {
                throw HavenError.PlaybackDecryptFailed("Chunk $index declares an invalid length ($length)")
            }
            val body = offset + CHUNK_HEADER_SIZE
            if (body + length > data.size) {
                throw HavenError.PlaybackDecryptFailed("Truncated chunk $index")
            }
            if (index != expectedIndex) {
                throw HavenError.PlaybackDecryptFailed("Chunk order mismatch: expected $expectedIndex, got $index")
            }
            val plain = aesGcmDecrypt(key, deriveChunkIv(baseIv, index), data.copyOfRange(body, body + length), aad)
            parts.add(plain)
            total += plain.size
            offset = body + length
            expectedIndex++
        }
        if (expectedIndex == 0) throw HavenError.PlaybackDecryptFailed("No chunks found")

        val out = ByteArray(total)
        var position = 0
        parts.forEach { part ->
            System.arraycopy(part, 0, out, position, part.size)
            position += part.size
        }
        return out
    }

    private fun decryptLegacy(key: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        if (data.size <= BASE_IV_SIZE + GCM_TAG_SIZE) {
            throw HavenError.PlaybackDecryptFailed("Payload too short to be AES-GCM")
        }
        val iv = data.copyOfRange(0, BASE_IV_SIZE)
        return aesGcmDecrypt(key, iv, data.copyOfRange(BASE_IV_SIZE, data.size), aad)
    }

    private fun deriveChunkIv(baseIv: ByteArray, chunkIndex: Int): ByteArray {
        require(baseIv.size == BASE_IV_SIZE) { "Base IV must be $BASE_IV_SIZE bytes" }
        val iv = baseIv.copyOf()
        val counter = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(chunkIndex.toLong()).array()
        for (i in 0 until 8) {
            iv[i + 4] = (iv[i + 4].toInt() xor counter[i].toInt()).toByte()
        }
        return iv
    }

    private fun aesGcmDecrypt(
        key: ByteArray,
        iv: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray?,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextAndTag)
    }
}

/**
 * Re-framing buffer for [HavenCipherImpl.decryptStream].
 *
 * Holds only the bytes that have arrived but not yet been decrypted — one partial chunk in the
 * steady state. Compacts in place instead of reallocating per read, because the alternative
 * (`ByteArray + copyOfRange` per chunk) turns a large file into thousands of short-lived
 * allocations and a GC pause in the middle of playback.
 */
private class FrameBuffer(initialCapacity: Int = 64 * 1024) {
    private var data = ByteArray(initialCapacity)
    private var readPosition = 0
    private var writePosition = 0

    val available: Int get() = writePosition - readPosition

    fun append(bytes: ByteArray) {
        ensureCapacity(bytes.size)
        System.arraycopy(bytes, 0, data, writePosition, bytes.size)
        writePosition += bytes.size
    }

    fun peekIntLe(offset: Int): Int {
        val base = readPosition + offset
        return (data[base].toInt() and 0xFF) or
            ((data[base + 1].toInt() and 0xFF) shl 8) or
            ((data[base + 2].toInt() and 0xFF) shl 16) or
            ((data[base + 3].toInt() and 0xFF) shl 24)
    }

    fun consume(count: Int): ByteArray {
        val out = data.copyOfRange(readPosition, readPosition + count)
        readPosition += count
        return out
    }

    fun skip(count: Int) {
        readPosition += count
    }

    private fun ensureCapacity(incoming: Int) {
        if (writePosition + incoming <= data.size) return

        // Reclaim consumed space first; only grow if the live window genuinely needs more.
        if (readPosition > 0) {
            System.arraycopy(data, readPosition, data, 0, available)
            writePosition = available
            readPosition = 0
        }
        if (writePosition + incoming <= data.size) return

        var capacity = data.size
        while (capacity < writePosition + incoming) capacity *= 2
        data = data.copyOf(capacity)
    }
}
