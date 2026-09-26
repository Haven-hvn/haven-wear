package haven.mobile.core.haven.aol

import dev.ic.kotlin.candid.CandidDecoder
import dev.ic.kotlin.candid.CandidEncoder
import dev.ic.kotlin.candid.CandidValue
import dev.ic.kotlin.candid.fieldId
import haven.mobile.core.crypto.AesKeyCache
import haven.mobile.core.domain.error.HavenError
import haven.mobile.core.haven.aol.vetkeys.TransportKeypair
import haven.mobile.core.haven.aol.vetkeys.UnwrapParams
import haven.mobile.core.haven.aol.vetkeys.VetKdUnwrap
import haven.mobile.core.wallet.WalletSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*

/**
 * Unit pins for the sealed v1 unlock path: decimal nonces, the derivation vector, threshold
 * normalization, signature validation, and the canister reply mapping — all against canned
 * values, no network and no native library.
 */
class SealedUnlockPrimitivesTest {

    private fun impl(unwrap: VetKdUnwrap = unavailableUnwrap()) = HavenAolImpl(
        HavenAolConfig(canisterId = "gny6k-fqaaa-aaaab-ag3ra-cai", icHost = "https://ic0.app"),
        fakeSession(),
        AesKeyCache(),
        NonceManager(),
        GateRequestBuilder(),
        unwrap,
    )

    @org.junit.Test
    fun `nonces are decimal uint256 in range and fresh`() {
        runBlocking {
            val manager = NonceManager()
            val first = manager.getNonce("0xabc", "canister")
            val second = manager.getNonce("0xabc", "canister")

            for (nonce in listOf(first, second)) {
                val value = java.math.BigInteger(nonce)
                assertTrue("nonce must be positive", value > java.math.BigInteger.ZERO)
                assertTrue(
                    "nonce must fit uint256",
                    value < java.math.BigInteger.ONE.shiftLeft(256),
                )
                assertTrue("nonce must be decimal", nonce.all { it in '0'..'9' })
            }
            assertNotEquals("nonces must be fresh (replay protection)", first, second)
        }
    }

    @org.junit.Test
    fun `derivation input matches the spec vector`() {
        // SHA-256("accessol:EthSepolia:0xtoken:1:sha256:abc") — any drift derives a key that
        // cannot open the sealed record.
        val expected = "446070e597bbfecdda38419e937ae9510aa8f0ddfdb1ac60cb4a807dfce7246a"

        val input = impl().vetkdDerivationInput("EthSepolia", "0xtoken", "1", "sha256:abc")

        assertEquals(32, input.size)
        assertEquals(expected, input.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
    }

    @org.junit.Test
    fun `threshold normalizes to a positive integer`() {
        val impl = impl()

        assertEquals("1", impl.normalizeSealedThreshold("1"))
        assertEquals("25", impl.normalizeSealedThreshold("25"))
        assertEquals("1", impl.normalizeSealedThreshold("0"))
        assertEquals("1", impl.normalizeSealedThreshold("-3"))
        assertEquals("1", impl.normalizeSealedThreshold(""))
        assertEquals("1", impl.normalizeSealedThreshold("abc"))
    }

    @org.junit.Test
    fun `wallet signatures must be 65-byte hex`() {
        val impl = impl()
        val good = "0x" + "11".repeat(65)

        assertEquals(65, impl.parseWalletSignature(good)!!.size)
        assertEquals(65, impl.parseWalletSignature(good.removePrefix("0x"))!!.size)
        assertNull(impl.parseWalletSignature("0x1234"))
        assertNull(impl.parseWalletSignature("0x" + "zz".repeat(65)))
        assertNull(impl.parseWalletSignature(""))
    }

    @org.junit.Test
    fun `ok reply yields both bundled keys`() {
        val enc = ByteArray(10) { it.toByte() }
        val vkey = ByteArray(48) { (it + 1).toByte() }
        val reply = CandidEncoder.encode(
            listOf(
                CandidValue.CandidVariant(
                    fieldId("ok"),
                    CandidValue.CandidRecord(
                        mapOf(
                            fieldId("encrypted_key") to CandidValue.CandidBlob(enc),
                            fieldId("verification_key") to CandidValue.CandidBlob(vkey),
                        ),
                    ),
                ),
            ),
        )

        val keys = impl().parseGateKeyResult(CandidDecoder.decode(reply)).getOrThrow()

        assertTrue(enc.contentEquals(keys.encryptedKey))
        assertTrue(vkey.contentEquals(keys.verificationKey))
    }

    @org.junit.Test
    fun `incomplete ok reply fails closed`() {
        val reply = CandidEncoder.encode(
            listOf(
                CandidValue.CandidVariant(
                    fieldId("ok"),
                    CandidValue.CandidRecord(
                        mapOf(fieldId("encrypted_key") to CandidValue.CandidBlob(byteArrayOf(1))),
                    ),
                ),
            ),
        )

        val result = impl().parseGateKeyResult(CandidDecoder.decode(reply))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is HavenError.CanisterCallFailed)
    }

    @org.junit.Test
    fun `err variants map to reader-facing failures`() {
        val impl = impl()

        val balance = errReply(
            "InsufficientBalance",
            CandidValue.CandidRecord(
                mapOf(
                    fieldId("required") to CandidValue.CandidNat(java.math.BigInteger("100")),
                    fieldId("actual") to CandidValue.CandidNat(java.math.BigInteger("3")),
                ),
            ),
        )
        val balanceErr = impl.parseGateKeyResult(balance).exceptionOrNull()
        assertTrue(balanceErr is HavenError.GateVerificationFailed)
        assertTrue(balanceErr!!.message.orEmpty().contains("100") && balanceErr.message.orEmpty().contains("3"))

        val sig = errReply("InvalidSignature", CandidValue.CandidText("bad"))
        assertTrue(impl.parseGateKeyResult(sig).exceptionOrNull() is HavenError.SigningFailed)

        val nonce = errReply("NonceAlreadyUsed", CandidValue.CandidNull)
        val nonceErr = impl.parseGateKeyResult(nonce).exceptionOrNull()
        assertTrue(nonceErr is HavenError.Internal)
        assertTrue(nonceErr!!.message.orEmpty().contains("already submitted"))

        val unknown = errReply("SomethingNew", CandidValue.CandidNull)
        assertTrue(impl.parseGateKeyResult(unknown).exceptionOrNull() is HavenError.CanisterCallFailed)
    }

    @org.junit.Test
    fun `non-variant reply fails closed`() {
        val reply = CandidEncoder.encode(listOf(CandidValue.CandidText("nope")))

        val result = impl().parseGateKeyResult(CandidDecoder.decode(reply))

        assertTrue(result.isFailure)
    }

    private fun errReply(tag: String, value: CandidValue): List<CandidValue> {
        val bytes = CandidEncoder.encode(
            listOf(
                CandidValue.CandidVariant(
                    fieldId("err"),
                    CandidValue.CandidVariant(fieldId(tag), value),
                ),
            ),
        )
        return CandidDecoder.decode(bytes)
    }

    private fun unavailableUnwrap() = object : VetKdUnwrap {
        override fun isAvailable(): Boolean = false
        override fun generateTransportKeypair(): Result<TransportKeypair> =
            Result.failure(IllegalStateException("no native lib"))
        override fun unwrapContentKey(params: UnwrapParams): Result<ByteArray> =
            Result.failure(IllegalStateException("no native lib"))
    }

    private fun fakeSession() = object : WalletSession {
        override val address = MutableStateFlow<String?>("0xabc")
        override val diagnostics = MutableStateFlow<List<String>>(emptyList())
        override val pairingUri = MutableStateFlow<String?>(null)
        override suspend fun connect(): Result<String> = Result.success("0xabc")
        override suspend fun disconnect() = Unit
        override suspend fun signTypedDataV4(json: String, chainId: Long): Result<String> =
            Result.failure(IllegalStateException("must not be reached"))
        override suspend fun sendTransaction(to: String, data: String, chainId: Long, valueHex: String): Result<String> =
            Result.failure(IllegalStateException("must not be reached"))
    }
}
