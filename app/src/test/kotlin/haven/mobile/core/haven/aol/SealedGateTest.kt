package haven.mobile.core.haven.aol

import dev.ic.kotlin.candid.CandidDecoder
import dev.ic.kotlin.candid.CandidEncoder
import dev.ic.kotlin.candid.CandidValue
import dev.ic.kotlin.candid.fieldId
import haven.mobile.core.crypto.AesKeyCache
import haven.mobile.core.domain.ArkivStatus
import haven.mobile.core.domain.ContentCacheStatus
import haven.mobile.core.domain.GateMetadata
import haven.mobile.core.domain.MediaItem
import haven.mobile.core.domain.MediaKind
import haven.mobile.core.domain.error.HavenError
import haven.mobile.core.haven.aol.vetkeys.TransportKeypair
import haven.mobile.core.haven.aol.vetkeys.UnwrapParams
import haven.mobile.core.haven.aol.vetkeys.VetKdUnwrap
import haven.mobile.core.wallet.WalletSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.json.JSONObject
import org.junit.Assert.*

/**
 * Sealed routing and the v1 unlock path end to end (minus network and native code): a canned
 * canister reply plus a fake unwrap prove the request construction, reply parsing, and key
 * recovery wiring — including that the canister request binds the record's own gate fields.
 */
class SealedGateTest {

    private val transportPub = ByteArray(48) { (it + 1).toByte() }
    private val transportSecret = ByteArray(32) { (it + 2).toByte() }
    private val encVetKey = ByteArray(96) { (it + 3).toByte() }
    private val verificationKey = ByteArray(48) { (it + 4).toByte() }
    private val aesKey = ByteArray(32) { (it + 5).toByte() }

    private fun cannedOkReply(): ByteArray = CandidEncoder.encode(
        listOf(
            CandidValue.CandidVariant(
                fieldId("ok"),
                CandidValue.CandidRecord(
                    mapOf(
                        fieldId("encrypted_key") to CandidValue.CandidBlob(encVetKey),
                        fieldId("verification_key") to CandidValue.CandidBlob(verificationKey),
                    ),
                ),
            ),
        ),
    )

    private open class CannedHavenAol(
        session: WalletSession,
        unwrap: VetKdUnwrap,
        private val reply: ByteArray,
    ) : HavenAolImpl(
        HavenAolConfig(canisterId = "gny6k-fqaaa-aaaab-ag3ra-cai", icHost = "https://ic0.app"),
        session,
        AesKeyCache(),
        NonceManager(),
        GateRequestBuilder(),
        unwrap,
    ) {
        var lastMethod: String? = null
        var lastArg: ByteArray? = null

        override suspend fun callCanister(method: String, candidArg: ByteArray): Result<ByteArray> {
            lastMethod = method
            lastArg = candidArg
            return Result.success(reply)
        }
    }

    private fun recordingUnwrap(onUnwrap: (UnwrapParams) -> Unit = {}) = object : VetKdUnwrap {
        override fun isAvailable(): Boolean = true
        override fun generateTransportKeypair(): Result<TransportKeypair> =
            Result.success(TransportKeypair(transportPub, transportSecret))
        override fun unwrapContentKey(params: UnwrapParams): Result<ByteArray> {
            onUnwrap(params)
            return Result.success(aesKey)
        }
    }

    private fun signingSession(onSign: (json: String, chainId: Long) -> Unit = { _, _ -> }) =
        object : WalletSession {
            override val address = MutableStateFlow<String?>("0xabc")
            override val diagnostics = MutableStateFlow<List<String>>(emptyList())
            override val pairingUri = MutableStateFlow<String?>(null)
            override suspend fun connect(): Result<String> = Result.success("0xabc")
            override suspend fun disconnect() = Unit
            override suspend fun signTypedDataV4(json: String, chainId: Long): Result<String> {
                onSign(json, chainId)
                return Result.success("0x" + "11".repeat(65))
            }
            override suspend fun sendTransaction(
                to: String,
                data: String,
                chainId: Long,
                valueHex: String,
            ): Result<String> = Result.failure(IllegalStateException("must not be reached"))
        }

    private fun sealedItem(version: Long = 1) = MediaItem(
        id = "0xabc", kind = MediaKind.VIDEO, owner = "0xabc", title = "sealed", description = null,
        mimeType = "video/mp4", fileExtension = ".mp4", filenameHint = null, sizeBytes = null,
        createdAt = Instant.fromEpochMilliseconds(0), createdAtBlock = 100, expiresAtBlock = null,
        pieceRef = null, filecoinCid = null, encryptedCid = null, cidHash = null,
        // No attribute gate on purpose: the sealed path binds the record's own fields.
        gate = null,
        isEncrypted = true,
        encryptionMetadata = GateMetadata.Sealed(
            version = version,
            encryptedAesKey = "U0VBTElORw==",
            cid = "sha256:abc",
            chain = "EthSepolia",
            tokenAddress = "0xtoken",
            threshold = "1",
        ),
        cidEncryptionMetadata = null,
        attestation = null, arkivStatus = ArkivStatus.FRESH, contentCacheStatus = ContentCacheStatus.UNCACHED,
        lastAccessedAt = null,
    )

    @org.junit.Test
    fun `sealed v1 decrypt recovers the content key`() {
        runBlocking {
            var seenParams: UnwrapParams? = null
            var signedJson: String? = null
            var signedChain: Long? = null
            val session = signingSession { json, chainId -> signedJson = json; signedChain = chainId }
            val impl = CannedHavenAol(session, recordingUnwrap { seenParams = it }, cannedOkReply())

            val result = impl.decrypt(sealedItem(), session)

            assertTrue(result.isSuccess)
            assertTrue(aesKey.contentEquals(result.getOrThrow()))
            // The wallet signed the canonical shape on the gate's own chain
            // (fixture gates EthSepolia), never the hardcoded dapp default.
            assertEquals(11155111L, signedChain)
            val signed = JSONObject(signedJson!!)
            assertEquals("GateRequest", signed.getString("primaryType"))
            assertEquals("0xabc", signed.getJSONObject("message").getString("evmAddress"))
            // The unwrap consumed the bundled reply plus the record's sealed key.
            val params = seenParams!!
            assertTrue(encVetKey.contentEquals(params.encryptedVetKey))
            assertTrue(verificationKey.contentEquals(params.verificationKey))
            assertEquals("U0VBTElORw==", params.sealedKeyUtf8.toString(Charsets.UTF_8))
            assertEquals(32, params.derivationInput.size)
        }
    }

    @org.junit.Test
    fun `canister request binds the record gate fields`() {
        runBlocking {
            val session = signingSession()
            val impl = CannedHavenAol(session, recordingUnwrap(), cannedOkReply())

            assertTrue(impl.decrypt(sealedItem(), session).isSuccess)

            assertEquals("requestDecryptionKey", impl.lastMethod)
            val record = CandidDecoder.decode(impl.lastArg!!).firstOrNull()
                as? CandidValue.CandidRecord ?: throw AssertionError("request must be a record")
            val fields = record.fields
            assertEquals(
                "sha256:abc",
                (fields[fieldId("cid")] as? CandidValue.CandidText)?.value,
            )
            assertEquals(
                "0xtoken",
                (fields[fieldId("tokenAddress")] as? CandidValue.CandidText)?.value,
            )
            assertEquals(
                java.math.BigInteger.ONE,
                (fields[fieldId("threshold")] as? CandidValue.CandidNat)?.value,
            )
            val chainTag = (fields[fieldId("chain")] as? CandidValue.CandidVariant)?.tag
            assertEquals(fieldId("EthSepolia"), chainTag)
            assertTrue(
                transportPub.contentEquals(
                    (fields[fieldId("transportPublicKey")] as? CandidValue.CandidBlob)?.bytes,
                ),
            )
            assertEquals(
                java.math.BigInteger.valueOf(11155111L),
                (fields[fieldId("eip712ChainId")] as? CandidValue.CandidNat)?.value,
            )
            assertEquals(
                "0x0000000000000000000000000000000000000000",
                (fields[fieldId("eip712VerifyingContract")] as? CandidValue.CandidText)?.value,
            )
        }
    }

    @org.junit.Test
    fun `unsupported seal version fails closed before signing`() {
        runBlocking {
            var signingAsked = false
            val session = signingSession { _, _ -> signingAsked = true }
            val impl = CannedHavenAol(session, recordingUnwrap(), cannedOkReply())

            val result = impl.decrypt(sealedItem(version = 2), session)

            val error = result.exceptionOrNull()
            assertTrue("expected UnsupportedGateMetadata, got $error", error is HavenError.UnsupportedGateMetadata)
            assertTrue(error!!.message.orEmpty().contains("v2"))
            assertFalse("wallet must not be asked to sign", signingAsked)
            assertNull("canister must not be called", impl.lastArg)
        }
    }

    @org.junit.Test
    fun `missing native library fails closed before signing`() {
        runBlocking {
            var signingAsked = false
            val session = signingSession { _, _ -> signingAsked = true }
            val noNative = object : VetKdUnwrap {
                override fun isAvailable(): Boolean = false
                override fun generateTransportKeypair(): Result<TransportKeypair> =
                    Result.failure(IllegalStateException("no native lib"))
                override fun unwrapContentKey(params: UnwrapParams): Result<ByteArray> =
                    Result.failure(IllegalStateException("no native lib"))
            }
            val impl = CannedHavenAol(session, noNative, cannedOkReply())

            val result = impl.decrypt(sealedItem(), session)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("native vetkeys library"))
            assertFalse("wallet must not be asked to sign", signingAsked)
        }
    }
}
