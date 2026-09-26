package haven.mobile.core.haven.aol

import dev.ic.kotlin.candid.CandidDecoder
import dev.ic.kotlin.candid.CandidEncoder
import dev.ic.kotlin.candid.CandidValue
import dev.ic.kotlin.candid.fieldId
import haven.mobile.core.crypto.AesKeyCache
import haven.mobile.core.crypto.Keccak256
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Protocol v3 (per-(gate, epoch)) unlock against the deployed canister's contract
 * (`haven-aol/src/backend/main.mo`, `backend.did`): request shape, derivation vectors,
 * and the "one signature per epoch" session behaviour. Network and native code are faked.
 */
class SealedV3UnlockTest {

    private val transportPub = ByteArray(48) { (it + 1).toByte() }
    private val encVetKey = ByteArray(192) { (it + 3).toByte() }
    private val verificationKey = ByteArray(96) { (it + 4).toByte() }

    private fun okReply(): ByteArray = CandidEncoder.encode(
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

    private class Recorder(
        val session: MutableSession = MutableSession(),
        val unwrap: FakeUnwrap = FakeUnwrap(),
    ) : HavenAolImpl(
        HavenAolConfig(canisterId = "gny6k-fqaaa-aaaab-ag3ra-cai", icHost = "https://ic0.app"),
        session,
        AesKeyCache(),
        NonceManager(),
        GateRequestBuilder(),
        unwrap,
    ) {
        var reply: ByteArray = ByteArray(0)
        val calls = mutableListOf<Pair<String, ByteArray>>()
        override suspend fun callCanister(method: String, candidArg: ByteArray): Result<ByteArray> {
            calls += method to candidArg
            return Result.success(reply)
        }
    }

    /** Derives a distinct fake AES key per sealed key and zeroes the secret like the JNI side. */
    private class FakeUnwrap : VetKdUnwrap {
        val seen = mutableListOf<UnwrapParams>()
        val secretsAtCall = mutableListOf<ByteArray>()
        override fun isAvailable() = true
        override fun generateTransportKeypair(): Result<TransportKeypair> =
            Result.success(TransportKeypair(ByteArray(48) { (it + 1).toByte() }, ByteArray(32) { 0x07 }))
        override fun unwrapContentKey(params: UnwrapParams): Result<ByteArray> {
            seen += params
            secretsAtCall += params.transportSecret.copyOf()
            params.transportSecret.fill(0)
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(params.sealedKeyUtf8)
            return Result.success(key)
        }
    }

    private class MutableSession(var declineSigning: Boolean = false) : WalletSession {
        val signed = mutableListOf<Pair<String, Long>>()
        override val address = MutableStateFlow<String?>("0x00000000000000000000000000000000000000ab")
        override val diagnostics = MutableStateFlow<List<String>>(emptyList())
        override val pairingUri = MutableStateFlow<String?>(null)
        override suspend fun connect(): Result<String> = Result.success(address.value!!)
        override suspend fun disconnect() = Unit
        override suspend fun signTypedDataV4(json: String, chainId: Long): Result<String> {
            signed += json to chainId
            return if (declineSigning) Result.failure(IllegalStateException("user rejected"))
            else Result.success("0x" + "11".repeat(64) + "1b")
        }
        override suspend fun sendTransaction(to: String, data: String, chainId: Long, valueHex: String): Result<String> =
            Result.failure(IllegalStateException("must not be reached"))
    }

    private fun v3Item(
        id: String,
        epoch: Long? = 680,
        threshold: String = "1",
        sealedKey: String = "SEALED-$id",
    ) = MediaItem(
        id = id, kind = MediaKind.AUDIO, owner = "0xowner", title = id, description = null,
        mimeType = "audio/mpeg", fileExtension = ".mp3", filenameHint = null, sizeBytes = null,
        createdAt = Instant.fromEpochMilliseconds(0), createdAtBlock = 123, expiresAtBlock = null,
        pieceRef = null, filecoinCid = null, encryptedCid = null, cidHash = null,
        gate = null,
        isEncrypted = true,
        encryptionMetadata = GateMetadata.Sealed(
            version = 3,
            encryptedAesKey = sealedKey,
            cid = "bafk-$id",
            chain = "EthSepolia",
            tokenAddress = "0xAbCdEf0000000000000000000000000000000001",
            threshold = threshold,
            epoch = epoch,
        ),
        cidEncryptionMetadata = null,
        attestation = null, arkivStatus = ArkivStatus.FRESH, contentCacheStatus = ContentCacheStatus.UNCACHED,
        lastAccessedAt = null,
    )

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun impl(): Triple<Recorder, MutableSession, FakeUnwrap> {
        val r = Recorder().also { it.reply = okReply() }
        return Triple(r, r.session, r.unwrap)
    }

    // ── Pinned vectors (haven-aol/tests/fixtures/derivation-v3-vectors.json) ───────────────

    @Test
    fun `derivation input matches the canister vectors`() {
        val r = Recorder()
        fun d(chain: String, token: String, thr: Long, epoch: Long) =
            hex(r.vetkdDerivationInputV3(chain, token, BigInteger.valueOf(thr), BigInteger.valueOf(epoch)))
        val usdc = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
        assertEquals("284079086e763ae6490c10a85ebd00f3b2f44369fef7d9798964ca4e0760860d", d("EthMainnet", usdc, 1, 670))
        assertEquals(
            "4755398ba6367830fc5db4a7974c2010091d56b5a48a4f86aff6ea23f079a7af",
            d("BaseMainnet", "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", 5, 671),
        )
        assertEquals("4e88a1834a21897e1a596ce9007bce59d5c49cb32078bcc12265508577bbad61", d("EthMainnet", usdc, 0, 0))
    }

    @Test
    fun `threshold zero collapses to epoch zero like the canister`() {
        val r = Recorder()
        val sealed = v3Item("a", epoch = 670, threshold = "0").encryptionMetadata as GateMetadata.Sealed
        val inputs = r.epochInputsOrError(sealed.copy(chain = "EthMainnet", tokenAddress = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"), "0xab")
            .getOrThrow()
        assertEquals("4e88a1834a21897e1a596ce9007bce59d5c49cb32078bcc12265508577bbad61", hex(inputs.derivationInput))
        assertEquals(BigInteger.ZERO, inputs.key.epoch)
        assertEquals("the request still carries the record's epoch", BigInteger.valueOf(670), inputs.requestEpoch)
    }

    @Test
    fun `typed data hashes to the canister's GateRequestV3 typehash`() {
        val json = JSONObject(
            GateRequestBuilder().buildV3Request("0xab", "0x0102", "680", "42", 11155111L),
        )
        val fields = json.getJSONObject("types").getJSONArray("GateRequestV3")
        val encodeType = "GateRequestV3(" + (0 until fields.length()).joinToString(",") {
            val f = fields.getJSONObject(it); "${f.getString("type")} ${f.getString("name")}"
        } + ")"
        assertEquals("bf3ae9382ccda27b087c12bfb5fd82fa7ccc60857623462a4c7fec696bc7d7af", Keccak256.hashHex(encodeType.toByteArray()))

        val domainFields = json.getJSONObject("types").getJSONArray("EIP712Domain")
        assertEquals("no version field in the domain", 3, domainFields.length())
        val domain = json.getJSONObject("domain")
        assertEquals("HavenAOL", domain.getString("name"))
        assertEquals(11155111L, domain.getLong("chainId"))
        assertEquals(GateRequestBuilder.EIP712_VERIFYING_CONTRACT, domain.getString("verifyingContract"))
        val message = json.getJSONObject("message")
        assertEquals(setOf("evmAddress", "transportPublicKey", "epoch", "nonce"), message.keySet())
        assertEquals("680", message.getString("epoch"))
    }

    // ── Unlock flow ───────────────────────────────────────────────────────────────────────

    @Test
    fun `v3 decrypt sends GateRequestV3 and unwraps the record key`() = runBlocking {
        val (r, session, unwrap) = impl()

        val result = r.decrypt(v3Item("a"), session)

        assertTrue("got ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(1, r.calls.size)
        assertEquals("requestDecryptionKeyV3", r.calls[0].first)
        val fields = (CandidDecoder.decode(r.calls[0].second).first() as CandidValue.CandidRecord).fields
        assertEquals(
            setOf("chain", "tokenAddress", "threshold", "epoch", "evmAddress", "transportPublicKey", "nonce",
                "signature", "eip712ChainId", "eip712VerifyingContract").map { fieldId(it) }.toSet(),
            fields.keys,
        )
        assertEquals(BigInteger.valueOf(680), (fields[fieldId("epoch")] as CandidValue.CandidNat).value)
        assertEquals(fieldId("EthSepolia"), (fields[fieldId("chain")] as CandidValue.CandidVariant).tag)
        assertEquals(BigInteger.valueOf(11155111L), (fields[fieldId("eip712ChainId")] as CandidValue.CandidNat).value)
        assertEquals(
            GateRequestBuilder.EIP712_VERIFYING_CONTRACT,
            (fields[fieldId("eip712VerifyingContract")] as CandidValue.CandidText).value,
        )
        assertArrayEquals(transportPub, (fields[fieldId("transportPublicKey")] as CandidValue.CandidBlob).bytes)

        // Signed the v3 struct on the gate's chain, with the same transport key and epoch.
        val (signedJson, chainId) = session.signed.single()
        assertEquals(11155111L, chainId)
        val message = JSONObject(signedJson).getJSONObject("message")
        assertEquals("0x" + hex(transportPub), message.getString("transportPublicKey"))
        assertEquals("680", message.getString("epoch"))

        val params = unwrap.seen.single()
        assertArrayEquals(encVetKey, params.encryptedVetKey)
        assertArrayEquals(verificationKey, params.verificationKey)
        assertEquals("SEALED-a", params.sealedKeyUtf8.toString(Charsets.UTF_8))
        assertArrayEquals(
            r.vetkdDerivationInputV3("EthSepolia", "0xAbCdEf0000000000000000000000000000000001", BigInteger.ONE, BigInteger.valueOf(680)),
            params.derivationInput,
        )
    }

    @Test
    fun `one signature opens every item of the same gate and epoch`() = runBlocking {
        val (r, session, unwrap) = impl()

        val a = r.decrypt(v3Item("a"), session).getOrThrow()
        assertTrue("second item reports no signature needed", r.hasCachedKey(v3Item("b")))
        val b = r.decrypt(v3Item("b"), session).getOrThrow()

        assertEquals("one wallet prompt", 1, session.signed.size)
        assertEquals("one canister call", 1, r.calls.size)
        assertFalse("each item opens its own encryptedAesKey", a.contentEquals(b))
        assertEquals(2, unwrap.seen.size)
        // The cached transport secret survives the first unwrap zeroing its copy.
        assertTrue(unwrap.secretsAtCall[1].any { it != 0.toByte() })

        r.decrypt(v3Item("c", epoch = 681), session).getOrThrow()
        assertEquals("a new epoch needs a new unlock", 2, session.signed.size)
    }

    @Test
    fun `decryptAll signs once per epoch and shares a declined signature`() = runBlocking {
        val (r, session, _) = impl()
        val items = listOf(v3Item("a"), v3Item("b"), v3Item("c"), v3Item("d", epoch = 681))

        val results = r.decryptAll(items, session) { _, _ -> }
        assertTrue(results.all { it.isSuccess })
        assertEquals(2, session.signed.size)

        val (r2, declining, _) = impl()
        declining.declineSigning = true
        val failed = r2.decryptAll(items.take(3), declining) { _, _ -> }
        assertTrue(failed.all { it.isFailure })
        assertEquals("declining once must not prompt again for the same epoch", 1, declining.signed.size)
    }

    @Test
    fun `disconnect wipes the epoch unlock`() = runBlocking {
        val (r, session, _) = impl()
        r.decrypt(v3Item("a"), session).getOrThrow()

        r.clearFor(session.address.value!!)

        assertFalse(r.hasCachedKey(v3Item("b")))
        r.decrypt(v3Item("b"), session).getOrThrow()
        assertEquals(2, session.signed.size)
    }

    @Test
    fun `incomplete v3 records fail closed before signing`() = runBlocking {
        val (r, session, _) = impl()

        val noEpoch = r.decrypt(v3Item("a", epoch = null), session).exceptionOrNull()
        val badThreshold = r.decrypt(v3Item("b", threshold = "1.5"), session).exceptionOrNull()

        assertTrue("$noEpoch", noEpoch is HavenError.UnsupportedGateMetadata)
        assertTrue("$badThreshold", badThreshold is HavenError.UnsupportedGateMetadata)
        assertTrue(session.signed.isEmpty())
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun `future epoch rejection maps to a readable error`() = runBlocking {
        val (r, session, _) = impl()
        r.reply = CandidEncoder.encode(
            listOf(CandidValue.CandidVariant(fieldId("err"), CandidValue.CandidVariant(fieldId("InvalidEpoch"), CandidValue.CandidNull))),
        )

        val error = r.decrypt(v3Item("a"), session).exceptionOrNull()

        assertTrue("$error", error?.message.orEmpty().contains("device clock"))
        assertFalse("a failed unlock is not cached", r.hasCachedKey(v3Item("b")))
    }
}
