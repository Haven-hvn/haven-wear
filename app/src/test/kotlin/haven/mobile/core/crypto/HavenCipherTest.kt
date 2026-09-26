package haven.mobile.core.crypto

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class HavenCipherTest {

    private val cipher = HavenCipherImpl()

    @Test
    fun `decrypt returns failure for garbage input`() = runBlocking {
        val key = ByteArray(32) { 1 }
        val ct = ByteArray(10) { 0 }
        val res = cipher.decrypt(key, ct, null)
        assertTrue(res.isFailure, "garbage should fail")
    }

    @Test
    fun `chunked round-trip decrypt succeeds`() = runBlocking {
        val key = ByteArray(32) { 0x42 }
        val plaintext = "Haven chunked decrypt test — offline-first".toByteArray()
        // Build chunked ciphertext using same format as haven-cli encrypt_file_streaming
        val baseIv = ByteArray(12) { it.toByte() }
        val chunkIndex = 0
        val perIv = deriveChunkIv(baseIv, chunkIndex)
        val aesEnc = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, perIv))
        }
        val encChunk = aesEnc.doFinal(plaintext)
        val buf = ByteBuffer.allocate(12 + 4 + 4 + encChunk.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(baseIv)
        buf.putInt(chunkIndex)
        buf.putInt(encChunk.size)
        buf.put(encChunk)
        val ciphertext = buf.array()
        val res = cipher.decrypt(key, ciphertext, null)
        assertTrue(res.isSuccess, "chunked decrypt should succeed: ${res.exceptionOrNull()}")
        assertArrayEquals(plaintext, res.getOrNull())
    }

    @Test
    fun `legacy single-block fallback decrypt`() = runBlocking {
        val key = ByteArray(32) { 0x11 }
        val iv = ByteArray(12) { 0x2A }
        val pt = "legacy single block".toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }.doFinal(pt)
        val ciphertext = iv + c
        val res = cipher.decrypt(key, ciphertext, null)
        assertTrue(res.isSuccess)
        assertArrayEquals(pt, res.getOrNull())
    }

    private fun deriveChunkIv(baseIv: ByteArray, idx: Int): ByteArray {
        val out = baseIv.copyOf()
        val idxBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(idx.toLong()).array()
        for (i in 0 until 8) out[4 + i] = (out[4 + i].toInt() xor idxBytes[i].toInt()).toByte()
        return out
    }
}
