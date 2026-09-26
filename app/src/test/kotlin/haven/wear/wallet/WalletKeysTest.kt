package haven.wear.wallet

import haven.mobile.core.crypto.Keccak256
import haven.mobile.core.haven.aol.GateRequestBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.crypto.StructuredDataEncoder
import java.math.BigInteger

/**
 * The watch key must produce exactly what the canister verifies. Every expected value here comes
 * from outside this codebase: the BIP-39/44 reference vector, and the typehash constants and digest
 * construction in haven-aol `src/backend/main.mo` (L234–257, L777–800), re-implemented below with
 * the ported Keccak256 — so web3j's encoder is checked against the canister, not against itself.
 */
class WalletKeysTest {

    private val zeroEntropy = ByteArray(16)

    // ── BIP-39 / BIP-44 ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `all-zero entropy is the standard abandon-about phrase`() {
        assertEquals(List(11) { "abandon" } + "about", WalletKeys.words(zeroEntropy))
    }

    @Test
    fun `abandon-about derives the well-known m-44-60-0-0-0 address`() {
        assertEquals("0x9858effd232b4033e47d90003d41ec34ecaeda94", WalletKeys.address(zeroEntropy))
        assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", WalletKeys.checksum(WalletKeys.address(zeroEntropy)))
    }

    @Test
    fun `entropy of the wrong size is refused`() {
        assertThrows(IllegalArgumentException::class.java) { WalletKeys.phrase(ByteArray(32)) }
    }

    // ── EIP-712 parity with main.mo ──────────────────────────────────────────────────────────────

    private val builder = GateRequestBuilder()
    private val address = "0x9858effd232b4033e47d90003d41ec34ecaeda94"
    private val transportKey = ByteArray(48) { (it * 7 + 1).toByte() }
    private val transportHex = "0x" + transportKey.hex()

    @Test
    fun `typehashes match the canister constants`() {
        val v1 = StructuredDataEncoder(builder.buildV1Request(address, transportHex, "1"))
        val v3 = StructuredDataEncoder(builder.buildV3Request(address, transportHex, "670", "1"))
        val v4 = StructuredDataEncoder(builder.buildV4Request(address, transportHex, "670", "5", "1"))
        assertEquals("8cad95687ba82c2ce50e74f7b754645e5117c3a5bec8151c0726d5857980a866", v1.typeHash("EIP712Domain").hex())
        assertEquals("88160239aa0076952ec94d7cf6b6b51da1765acd803b051b6d06b3f27623f2c0", v1.typeHash("GateRequest").hex())
        assertEquals("bf3ae9382ccda27b087c12bfb5fd82fa7ccc60857623462a4c7fec696bc7d7af", v3.typeHash("GateRequestV3").hex())
        assertEquals("b9d5f143468a4d6e11bd1d2ff3eb546445b99a1e871adde2cd2c6008e2980afd", v4.typeHash("GateRequestV4").hex())
    }

    @Test
    fun `v1 digest equals the canister's hand-built digest`() {
        val chainId = 8453L
        val nonce = BigInteger("12345678901234567890")
        val json = builder.buildV1Request(address, transportHex, nonce.toString(), domainChainId = chainId)
        val structHash = keccak(
            hex("88160239aa0076952ec94d7cf6b6b51da1765acd803b051b6d06b3f27623f2c0") +
                word(address) + keccak(transportKey) + word(nonce),
        )
        assertEquals(canisterDigest(chainId, structHash).hex(), StructuredDataEncoder(json).hashStructuredData().hex())
    }

    @Test
    fun `v4 digest equals the canister's hand-built digest`() {
        val chainId = 8453L
        val json = builder.buildV4Request(address, transportHex, "670", "5", "42", domainChainId = chainId)
        val structHash = keccak(
            hex("b9d5f143468a4d6e11bd1d2ff3eb546445b99a1e871adde2cd2c6008e2980afd") +
                word(address) + keccak(transportKey) + word(BigInteger.valueOf(670)) + word(BigInteger.valueOf(5)) + word(BigInteger.valueOf(42)),
        )
        assertEquals(canisterDigest(chainId, structHash).hex(), StructuredDataEncoder(json).hashStructuredData().hex())
    }

    @Test
    fun `signature is 65 bytes, v in 27-28, and recovers to the watch address`() {
        val json = builder.buildV3Request(address, transportHex, "670", "7", domainChainId = 1L)
        val sigHex = WalletKeys.withKeyPair(zeroEntropy) { WalletKeys.signTypedData(json, it) }

        assertTrue(sigHex.startsWith("0x") && sigHex.length == 132)
        val sig = hex(sigHex.removePrefix("0x"))
        val v = sig[64].toInt() and 0xFF
        assertTrue(v == 27 || v == 28)

        val recovered = Sign.signedMessageHashToKey(
            StructuredDataEncoder(json).hashStructuredData(),
            Sign.SignatureData(sig[64], sig.copyOfRange(0, 32), sig.copyOfRange(32, 64)),
        )
        assertEquals(address, "0x" + Keys.getAddress(recovered))
    }

    // ── Signing policy ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `policy accepts Haven gate requests for the signer`() {
        HavenTypedDataPolicy.check(builder.buildV4Request(address, transportHex, "670", "5", "1", domainChainId = 8453L), 8453L, address)
        HavenTypedDataPolicy.check(builder.buildV1Request(address.uppercase().replace("0X", "0x"), transportHex, "1"), 1L, address)
    }

    @Test
    fun `policy refuses anything else`() {
        val permit = """{"types":{"EIP712Domain":[],"Permit":[]},"primaryType":"Permit","domain":{"name":"USD Coin","chainId":1},"message":{"evmAddress":"$address"}}"""
        assertThrows(IllegalArgumentException::class.java) { HavenTypedDataPolicy.check(permit, 1L, address) }

        val otherDomain = builder.buildV1Request(address, transportHex, "1").replace("\"HavenAOL\"", "\"Seaport\"")
        assertThrows(IllegalArgumentException::class.java) { HavenTypedDataPolicy.check(otherDomain, 1L, address) }

        val wrongChain = builder.buildV1Request(address, transportHex, "1", domainChainId = 10L)
        assertThrows(IllegalArgumentException::class.java) { HavenTypedDataPolicy.check(wrongChain, 1L, address) }

        val otherSigner = builder.buildV1Request("0x" + "11".repeat(20), transportHex, "1")
        assertThrows(IllegalArgumentException::class.java) { HavenTypedDataPolicy.check(otherSigner, 1L, address) }
    }

    // ── helpers: main.mo's encoding, verbatim ────────────────────────────────────────────────────

    private fun canisterDigest(chainId: Long, structHash: ByteArray): ByteArray {
        val domain = keccak(
            hex("8cad95687ba82c2ce50e74f7b754645e5117c3a5bec8151c0726d5857980a866") +
                keccak("HavenAOL".toByteArray()) + word(BigInteger.valueOf(chainId)) +
                word(GateRequestBuilder.EIP712_VERIFYING_CONTRACT),
        )
        return keccak(hex("1901") + domain + structHash)
    }

    private fun keccak(b: ByteArray) = Keccak256.hash(b)

    private fun word(address: String): ByteArray = ByteArray(12) + hex(address.removePrefix("0x"))

    private fun word(n: BigInteger): ByteArray {
        val raw = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - raw.size) + raw
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
