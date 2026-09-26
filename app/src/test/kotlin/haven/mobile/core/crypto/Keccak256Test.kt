package haven.mobile.core.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Keccak-256 pins. Vectors cross-checked against BouncyCastle's KeccakDigest
 * (empty/abc match the published values; a200 spans two blocks, fox one).
 * Any drift in the permutation, padding, or round constants fails loudly here
 * instead of as an `InvalidSignature` from the canister at unlock time.
 */
class Keccak256Test {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `empty input`() {
        assertEquals(
            "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            hex(Keccak256.hash(ByteArray(0))),
        )
    }

    @Test
    fun `abc`() {
        assertEquals(
            "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45",
            hex(Keccak256.hash("abc".toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun `multi-block input`() {
        val input = ByteArray(200) { 'a'.code.toByte() }
        assertEquals(
            "96ea54061def936c4be90b518992fdc6f12f535068a256229aca54267b4d084d",
            hex(Keccak256.hash(input)),
        )
    }

    @Test
    fun `fox sentence`() {
        assertEquals(
            "4d741b6f1eb29cb2a9b9911c82f56fa8d73b04959d3d9d222895df6c0b28aa15",
            hex(Keccak256.hash("The quick brown fox jumps over the lazy dog".toByteArray(Charsets.UTF_8))),
        )
    }
}
