package haven.mobile.core.crypto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The streaming path is what makes Haven usable on a low-memory device, so these tests care about
 * two things: that it decrypts correctly regardless of how the transport chops the bytes up, and
 * that it emits *as it goes* rather than buffering the payload and emitting once at the end.
 */
class HavenCipherStreamTest {

    private val cipher = HavenCipherImpl()
    private val key = ByteArray(32) { 0x37 }

    @Test
    fun `emits one plaintext run per encryption chunk`() = runBlocking {
        val chunks = listOf(
            "chunk-zero-".repeat(4).toByteArray(),
            "chunk-one-".repeat(4).toByteArray(),
            "chunk-two-".repeat(4).toByteArray(),
        )
        val ciphertext = frame(chunks)

        val emitted = cipher.decryptStream(key, flowOfBytes(listOf(ciphertext)), null).toList()

        assertEquals(3, emitted.size, "one emission per encryption chunk")
        assertArrayEquals(chunks[0], emitted[0])
        assertArrayEquals(chunks[1], emitted[1])
        assertArrayEquals(chunks[2], emitted[2])
    }

    @Test
    fun `transport boundaries do not have to align with chunk boundaries`() = runBlocking {
        val plaintextChunks = List(5) { index -> "payload-$index-".repeat(8).toByteArray() }
        val ciphertext = frame(plaintextChunks)

        // Worst case: one byte at a time. If any framing state were kept per-emission rather than
        // across the stream, this would fail.
        val perByte = ciphertext.map { byteArrayOf(it) }
        val emitted = cipher.decryptStream(key, flowOfBytes(perByte), null).toList()

        assertArrayEquals(plaintextChunks.flatten(), emitted.flatten())
    }

    @Test
    fun `odd transport sizes reassemble correctly`() = runBlocking {
        val plaintextChunks = List(4) { index -> "block-$index-".repeat(16).toByteArray() }
        val ciphertext = frame(plaintextChunks)

        listOf(7, 13, 64, 999).forEach { size ->
            val emitted = cipher.decryptStream(key, flowOfBytes(ciphertext.slices(size)), null).toList()
            assertArrayEquals(
                plaintextChunks.flatten(),
                emitted.flatten(),
                "reassembly failed at transport size $size",
            )
        }
    }

    @Test
    fun `plaintext is emitted before the stream completes`() = runBlocking {
        // The regression guard. The previous implementation collected the whole flow into one buffer
        // before decrypting anything, so nothing could be written to disk until the last byte
        // arrived — the exact behaviour that made large files unusable on a small device.
        val plaintextChunks = List(3) { index -> "early-$index-".repeat(8).toByteArray() }
        val ciphertext = frame(plaintextChunks)
        val head = ciphertext.copyOfRange(0, ciphertext.size / 2)
        val tail = ciphertext.copyOfRange(ciphertext.size / 2, ciphertext.size)

        // The source waits for downstream plaintext before releasing the second half. If the
        // implementation buffers, this wait times out and the assertion fails.
        val firstPlaintext = CompletableDeferred<Unit>()
        var sawPlaintextBeforeTail = false
        val source = flow {
            emit(head)
            sawPlaintextBeforeTail = withTimeoutOrNull(GATE_TIMEOUT_MS) { firstPlaintext.await() } != null
            emit(tail)
        }

        val emitted = mutableListOf<ByteArray>()
        cipher.decryptStream(key, source, null).collect { plain ->
            emitted.add(plain)
            if (!firstPlaintext.isCompleted) firstPlaintext.complete(Unit)
        }

        assertTrue(
            sawPlaintextBeforeTail,
            "expected plaintext from the first half of the stream before the second half was sent",
        )
        assertArrayEquals(plaintextChunks.flatten(), emitted.flatten())
    }

    @Test
    fun `a chunk with an implausible length is rejected`() = runBlocking {
        val ciphertext = frame(listOf("small".toByteArray()))
        // Overwrite the first chunk's length field with something enormous.
        ByteBuffer.wrap(ciphertext).order(ByteOrder.LITTLE_ENDIAN).putInt(16, Int.MAX_VALUE)

        val failure = runCatching {
            cipher.decryptStream(key, flowOfBytes(listOf(ciphertext)), null).toList()
        }.exceptionOrNull()

        assertTrue(failure != null, "an invalid chunk length must fail rather than allocate")
    }

    @Test
    fun `a truncated stream fails instead of silently returning a short file`() = runBlocking {
        val ciphertext = frame(listOf("complete-chunk".repeat(4).toByteArray()))
        val truncated = ciphertext.copyOfRange(0, ciphertext.size - 8)

        val failure = runCatching {
            cipher.decryptStream(key, flowOfBytes(listOf(truncated)), null).toList()
        }.exceptionOrNull()

        assertTrue(failure != null, "truncation must surface as an error")
    }

    @Test
    fun `out of order chunks fail`() = runBlocking {
        val ciphertext = frame(listOf("a".repeat(32).toByteArray(), "b".repeat(32).toByteArray()))
        // Rewrite the first chunk's index so the sequence starts at 1.
        ByteBuffer.wrap(ciphertext).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 1)

        val failure = runCatching {
            cipher.decryptStream(key, flowOfBytes(listOf(ciphertext)), null).toList()
        }.exceptionOrNull()

        assertTrue(failure != null, "a wrong chunk index means a wrong IV and must not be decrypted")
    }

    @Test
    fun `legacy unframed payloads still stream`() = runBlocking {
        val plaintext = "legacy single block payload".toByteArray()
        val iv = ByteArray(12) { 0x5A }
        val body = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }.doFinal(plaintext)

        val emitted = cipher.decryptStream(key, flowOfBytes((iv + body).slices(9)), null).toList()

        assertArrayEquals(plaintext, emitted.flatten())
    }

    /** Generous: this only has to outlast a decrypt of a few hundred bytes. */
    private val GATE_TIMEOUT_MS = 2_000L

    private fun flowOfBytes(parts: List<ByteArray>) = flow {
        parts.forEach { emit(it) }
    }

    /** Frames plaintext chunks exactly as `haven-cli`'s streaming encryptor does. */
    private fun frame(plaintextChunks: List<ByteArray>): ByteArray {
        val baseIv = ByteArray(12) { it.toByte() }
        val encrypted = plaintextChunks.mapIndexed { index, plain ->
            val iv = deriveChunkIv(baseIv, index)
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            }.doFinal(plain)
        }
        val total = 12 + encrypted.sumOf { 8 + it.size }
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(baseIv)
        encrypted.forEachIndexed { index, chunk ->
            buffer.putInt(index)
            buffer.putInt(chunk.size)
            buffer.put(chunk)
        }
        return buffer.array()
    }

    private fun deriveChunkIv(baseIv: ByteArray, index: Int): ByteArray {
        val iv = baseIv.copyOf()
        val counter = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(index.toLong()).array()
        for (i in 0 until 8) iv[4 + i] = (iv[4 + i].toInt() xor counter[i].toInt()).toByte()
        return iv
    }

    private fun ByteArray.slices(size: Int): List<ByteArray> =
        toList().chunked(size).map { it.toByteArray() }

    private fun List<ByteArray>.flatten(): ByteArray {
        val out = ByteArray(sumOf { it.size })
        var position = 0
        forEach { part ->
            System.arraycopy(part, 0, out, position, part.size)
            position += part.size
        }
        return out
    }
}
