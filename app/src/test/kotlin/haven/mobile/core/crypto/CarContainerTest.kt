package haven.mobile.core.crypto

import haven.mobile.core.domain.error.HavenError
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Retrieval serves the stored CAR file; [HavenCipher] expects the raw chunked ciphertext
 * inside it. These pin the strip step with synthetic containers (no fixtures needed):
 * single-block CARs pass through byte-identical under any network chunking, flat
 * multi-block raws reassemble when the dag-pb root names them in order, and anything
 * else either passes through untouched (legacy) or fails loud.
 */
class CarContainerTest {

    private val cipher = HavenCipherImpl()
    private val key = ByteArray(32) { 0x37 }

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = mutableListOf<Byte>()
        do {
            var b = (v and 0x7F).toByte()
            v = v ushr 7
            if (v != 0L) b = (b.toInt() or 0x80).toByte()
            out += b
        } while (v != 0L)
        return out.toByteArray()
    }

    private fun carHeader(): ByteArray {
        val roots = byteArrayOf(0x01, 0x55, 0x12, 0x20) + ByteArray(32) { 0x11 }
        val header = byteArrayOf(0xA2.toByte(), 0x67.toByte()) +
            "version".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01) +
            byteArrayOf(0x65.toByte()) + "roots".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x81.toByte()) + roots
        return varint(header.size.toLong()) + header
    }

    private fun carBlock(cid: ByteArray, data: ByteArray): ByteArray =
        varint((cid.size + data.size).toLong()) + cid + data

    private fun rawCid(seed: Byte = 0x11): ByteArray =
        byteArrayOf(0x01, 0x55, 0x12, 0x20) + ByteArray(32) { (seed + it).toByte() }

    private fun sha256(bytes: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Minimal dag-pb root node linking the given leaf CIDs, in order. */
    private fun dagPbRoot(leafCids: List<ByteArray>): Pair<ByteArray, ByteArray> {
        val out = mutableListOf<Byte>()
        leafCids.forEachIndexed { index, cid ->
            val multihash = cid.copyOfRange(2, cid.size)
            val link = mutableListOf<Byte>()
            link += 0x0A.toByte()
            link.addAll(varint(multihash.size.toLong()).toList())
            link.addAll(multihash.toList())
            link += 0x18.toByte()
            link.addAll(varint((100 + index).toLong()).toList())
            out += 0x12.toByte()
            out.addAll(varint(link.size.toLong()).toList())
            out.addAll(link)
        }
        val data = out.toByteArray()
        val cid = byteArrayOf(0x01, 0x70, 0x12, 0x20) + sha256(data)
        return cid to data
    }

    private fun carHeaderFor(rootCid: ByteArray): ByteArray {
        // Definite byte-string with one-byte length (0x58), like real encoders emit.
        val header = byteArrayOf(0xA2.toByte(), 0x67.toByte()) +
            "version".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01) +
            byteArrayOf(0x65.toByte()) + "roots".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x81.toByte()) +
            byteArrayOf(0x58.toByte(), rootCid.size.toByte()) + rootCid
        return varint(header.size.toLong()) + header
    }

    private fun chunkedCiphertext(plaintext: ByteArray): ByteArray {
        val baseIv = ByteArray(12) { it.toByte() }
        val out = mutableListOf<Byte>()
        out.addAll(baseIv.toList())
        var index = 0
        plaintext.toList().chunked(64).forEach { piece ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = baseIv.copyOf()
            val counter = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(index.toLong()).array()
            for (i in 0 until 8) iv[i + 4] = (iv[i + 4].toInt() xor counter[i].toInt()).toByte()
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(piece.toByteArray())
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(index).putInt(ct.size).array()
            out.addAll(header.toList())
            out.addAll(ct.toList())
            index++
        }
        return out.toByteArray()
    }

    private fun split(bytes: ByteArray, sizes: List<Int>): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var pos = 0
        for (size in sizes) {
            if (pos >= bytes.size) break
            val end = minOf(pos + size, bytes.size)
            out += bytes.copyOfRange(pos, end)
            pos = end
        }
        if (pos < bytes.size) out += bytes.copyOfRange(pos, bytes.size)
        return out
    }

    @Test
    fun `single block car strips under hostile chunking`() = runBlocking {
        val data = ByteArray(300) { it.toByte() }
        val car = carHeader() + carBlock(rawCid(), data)
        // Chop through the varints, the CID, and mid-payload.
        val chunks = split(car, listOf(1, 2, 5, 13, 37, 64))
        val stripped = flow { chunks.forEach { emit(it) } }.stripCarContainer().toList()
        assertArrayEquals(data, stripped.fold(ByteArray(0)) { acc, b -> acc + b })
    }

    @Test
    fun `non-car bytes pass through identical`() = runBlocking {
        val raw = chunkedCiphertext(ByteArray(200) { (it * 3).toByte() })
        val chunks = split(raw, listOf(7, 100))
        val out = flow { chunks.forEach { emit(it) } }.stripCarContainer().toList()
            .fold(ByteArray(0)) { acc, b -> acc + b }
        assertArrayEquals(raw, out)
    }

    @Test
    fun `stripped car decrypts through the cipher`() = runBlocking {
        val plaintext = ByteArray(200) { (it * 3).toByte() }
        val car = carHeader() + carBlock(rawCid(0x22), chunkedCiphertext(plaintext))
        val stripped = flowOf(car).stripCarContainer()
        val decrypted = cipher.decryptStream(key, stripped, null).toList()
            .fold(ByteArray(0)) { acc, b -> acc + b }
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `multi-block car fails loud`() {
        val car = carHeader() + carBlock(rawCid(0x22), byteArrayOf(1, 2, 3)) +
            carBlock(rawCid(0x33), byteArrayOf(4, 5, 6))
        val error = assertThrows(HavenError.PlaybackDecryptFailed::class.java) {
            runBlocking { flowOf(car).stripCarContainer().toList() }
        }
        assertTrue(error.message!!.contains("Multi-block"))
    }

    @Test
    fun `flat multi-block raw leaves reassemble in root order`() = runBlocking {
        val leafA = rawCid(0x22)
        val leafB = rawCid(0x33)
        val (rootCid, rootData) = dagPbRoot(listOf(leafA, leafB))
        val car = carHeaderFor(rootCid) +
            carBlock(leafA, byteArrayOf(1, 2, 3)) +
            carBlock(leafB, byteArrayOf(4, 5, 6)) +
            carBlock(rootCid, rootData)
        // Hostile chunking: through CIDs, frames, and the root node.
        val chunks = split(car, listOf(3, 41, 7, 200, 13))
        val out = flow { chunks.forEach { emit(it) } }.stripCarContainer().toList()
            .fold(ByteArray(0)) { acc, b -> acc + b }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), out)
    }

    @Test
    fun `root first order also reassembles`() = runBlocking {
        val leafA = rawCid(0x22)
        val leafB = rawCid(0x33)
        val (rootCid, rootData) = dagPbRoot(listOf(leafA, leafB))
        val car = carHeaderFor(rootCid) +
            carBlock(rootCid, rootData) +
            carBlock(leafA, byteArrayOf(1, 2, 3)) +
            carBlock(leafB, byteArrayOf(4, 5, 6))
        val out = flowOf(car).stripCarContainer().toList()
            .fold(ByteArray(0)) { acc, b -> acc + b }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), out)
    }

    @Test
    fun `reordered leaves against the root fail loud`() {
        val leafA = rawCid(0x22)
        val leafB = rawCid(0x33)
        val (rootCid, rootData) = dagPbRoot(listOf(leafB, leafA))
        val car = carHeaderFor(rootCid) +
            carBlock(leafA, byteArrayOf(1, 2, 3)) +
            carBlock(leafB, byteArrayOf(4, 5, 6)) +
            carBlock(rootCid, rootData)
        val error = assertThrows(HavenError.PlaybackDecryptFailed::class.java) {
            runBlocking { flowOf(car).stripCarContainer().toList() }
        }
        assertTrue(error.message!!.contains("does not match"))
    }

    @Test
    fun `nested dag-pb node fails loud`() {
        val leaf = rawCid(0x22)
        val (rootCid, rootData) = dagPbRoot(listOf(leaf))
        val (otherCid, otherData) = dagPbRoot(listOf(rawCid(0x44)))
        val car = carHeaderFor(rootCid) +
            carBlock(leaf, byteArrayOf(1, 2, 3)) +
            carBlock(otherCid, otherData) +
            carBlock(rootCid, rootData)
        val error = assertThrows(HavenError.PlaybackDecryptFailed::class.java) {
            runBlocking { flowOf(car).stripCarContainer().toList() }
        }
        assertTrue(error.message!!.contains("Nested"))
    }

    @Test
    fun `multi-block ciphertext decrypts end to end`() = runBlocking {
        val plaintext = ByteArray(500) { (it * 7).toByte() }
        val ct = chunkedCiphertext(plaintext)
        val mid = ct.size / 2
        val leafA = rawCid(0x22)
        val leafB = rawCid(0x33)
        val (rootCid, rootData) = dagPbRoot(listOf(leafA, leafB))
        val car = carHeaderFor(rootCid) +
            carBlock(leafA, ct.copyOfRange(0, mid)) +
            carBlock(leafB, ct.copyOfRange(mid, ct.size)) +
            carBlock(rootCid, rootData)
        val stripped = flowOf(car).stripCarContainer()
        val decrypted = cipher.decryptStream(key, stripped, null).toList()
            .fold(ByteArray(0)) { acc, b -> acc + b }
        assertArrayEquals(plaintext, decrypted)
    }

    /**
     * Byte-faithful single-block CAR, mirroring `js-services/single-block-car.ts`: one raw
     * block whose frame dwarfs the prefix cap, header roots wrapped as tag-42 +
     * multibase-identity-prefixed CID.
     */
    private fun singleBlockCar(data: ByteArray): ByteArray {
        val digest = sha256(data)
        val cid = byteArrayOf(0x01, 0x55, 0x12, 0x20) + digest
        val headerBody = byteArrayOf(0xA2.toByte(), 0x65.toByte()) +
            "roots".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x81.toByte(), 0xD8.toByte(), 0x2A.toByte(), 0x58.toByte(), 0x25.toByte(), 0x00.toByte()) +
            cid +
            byteArrayOf(0x67.toByte()) + "version".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01)
        return varint(headerBody.size.toLong()) + headerBody + carBlock(cid, data)
    }

    @Test
    fun `single-block car with a frame larger than the prefix cap still strips`() = runBlocking {
        // Regression: the 8.3MB MP3 packs as ONE 8MB frame. decide() used to demand the whole
        // first frame before committing to CAR, but the pending cap (4KB) fired first — the
        // container sailed through raw, the cipher ate the CAR header as its IV, and every
        // chunk failed its tag check (BAD_DECRYPT, ptBytes=0, key provably correct).
        val data = ByteArray(200 * 1024) { (it * 5).toByte() }
        val car = singleBlockCar(data)
        // Network-sized reads: the header + CID confirm CAR long before the frame completes.
        val chunks = split(car, List(300) { 997 })
        val out = flow { chunks.forEach { emit(it) } }.stripCarContainer().toList()
            .fold(ByteArray(0)) { acc, b -> acc + b }
        assertArrayEquals(data, out)
    }

    @Test
    fun `single-block car decrypts end to end through the phone pipeline`() = runBlocking {
        // The exact phone path: stripCarContainer -> decryptStream, over hostile chunking.
        val plaintext = ByteArray(200 * 1024) { (it * 5).toByte() }
        val car = singleBlockCar(chunkedCiphertext(plaintext))
        val chunks = split(car, List(300) { 997 })
        val stripped = flow { chunks.forEach { emit(it) } }.stripCarContainer()
        val decrypted = cipher.decryptStream(key, stripped, null).toList()
            .fold(ByteArray(0)) { acc, b -> acc + b }
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `truncated prefix fails open`() = runBlocking {
        // Dies mid-header: not provably a container, so pass through for the decrypt
        // layer to judge rather than failing here.
        val partial = carHeader().copyOfRange(0, 3)
        val out = flowOf(partial).stripCarContainer().toList()
            .fold(ByteArray(0)) { acc, b -> acc + b }
        assertArrayEquals(partial, out)
    }

    @Test
    fun `empty stream stays empty`() = runBlocking {
        assertEquals(0, flowOf<ByteArray>().stripCarContainer().toList().size)
    }
}
