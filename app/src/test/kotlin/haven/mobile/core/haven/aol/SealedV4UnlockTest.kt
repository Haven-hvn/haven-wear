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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * gate_type 4 (market-cap drip) unlock against the deployed canister's contract
 * (`haven-aol/src/backend/main.mo`: `requestDecryptionKeyV4`, `getMarketCap`), plus the
 * gate_type / gate-version cross-check. Network and native code are faked.
 */
class SealedV4UnlockTest {

    private val encVetKey = ByteArray(192) { (it + 3).toByte() }
    private val verificationKey = ByteArray(96) { (it + 4).toByte() }
    private val bond = "0x5555555555555555555555555555555555555555"
    private val token = "0xAa70bC79fD1cB4a6FBA717018351F0C3c64B79Df"

    private fun okKeyReply(): ByteArray = CandidEncoder.encode(
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

    private fun capReply(cap: Long): ByteArray = CandidEncoder.encode(
        listOf(CandidValue.CandidVariant(fieldId("ok"), CandidValue.CandidNat(BigInteger.valueOf(cap)))),
    )

    private fun notReachedReply(required: Long, actual: Long): ByteArray = CandidEncoder.encode(
        listOf(
            CandidValue.CandidVariant(
                fieldId("err"),
                CandidValue.CandidVariant(
                    fieldId("MarketCapNotReached"),
                    CandidValue.CandidRecord(
                        mapOf(
                            fieldId("required") to CandidValue.CandidNat(BigInteger.valueOf(required)),
                            fieldId("actual") to CandidValue.CandidNat(BigInteger.valueOf(actual)),
                        ),
                    ),
                ),
            ),
        ),
    )

    private class Session : WalletSession {
        val signed = mutableListOf<Pair<String, Long>>()
        override val address = MutableStateFlow<String?>("0x00000000000000000000000000000000000000ab")
        override val diagnostics = MutableStateFlow<List<String>>(emptyList())
        override val pairingUri = MutableStateFlow<String?>(null)
        override suspend fun connect(): Result<String> = Result.success(address.value!!)
        override suspend fun disconnect() = Unit
        override suspend fun signTypedDataV4(json: String, chainId: Long): Result<String> {
            signed += json to chainId
            return Result.success("0x" + "11".repeat(64) + "1b")
        }
        override suspend fun sendTransaction(to: String, data: String, chainId: Long, valueHex: String): Result<String> =
            Result.failure(IllegalStateException("must not be reached"))
    }

    private class Unwrap : VetKdUnwrap {
        val seen = mutableListOf<UnwrapParams>()
        override fun isAvailable() = true
        override fun generateTransportKeypair(): Result<TransportKeypair> =
            Result.success(TransportKeypair(ByteArray(48) { (it + 1).toByte() }, ByteArray(32) { 0x07 }))
        override fun unwrapContentKey(params: UnwrapParams): Result<ByteArray> {
            seen += params
            params.transportSecret.fill(0)
            return Result.success(java.security.MessageDigest.getInstance("SHA-256").digest(params.sealedKeyUtf8))
        }
    }

    /** Routes canned replies per method; [cap] drives `getMarketCap`. */
    private class Canister(
        val session: Session = Session(),
        val unwrap: Unwrap = Unwrap(),
    ) : HavenAolImpl(
        HavenAolConfig(canisterId = "gny6k-fqaaa-aaaab-ag3ra-cai", icHost = "https://ic0.app"),
        session,
        AesKeyCache(),
        NonceManager(),
        GateRequestBuilder(),
        unwrap,
    ) {
        var replies: MutableMap<String, ByteArray> = mutableMapOf()
        val calls = mutableListOf<Pair<String, ByteArray>>()
        override suspend fun callCanister(method: String, candidArg: ByteArray): Result<ByteArray> {
            calls += method to candidArg
            return replies[method]?.let { Result.success(it) }
                ?: Result.failure(HavenError.CanisterCallFailed("no canned reply for $method"))
        }
        fun methods() = calls.map { it.first }
    }

    private fun canister(cap: Long = 20): Canister = Canister().also {
        it.replies["getMarketCap"] = capReply(cap)
        it.replies["requestDecryptionKeyV4"] = okKeyReply()
    }

    private fun v4Item(
        id: String,
        target: Long? = 10,
        oracle: String = bond,
        epoch: Long = 670,
        attributeGateType: Long? = 4,
    ) = MediaItem(
        id = id, kind = MediaKind.VIDEO, owner = "0xowner", title = id, description = null,
        mimeType = "video/mp4", fileExtension = ".mp4", filenameHint = null, sizeBytes = null,
        createdAt = Instant.fromEpochMilliseconds(0), createdAtBlock = 1, expiresAtBlock = null,
        pieceRef = null, filecoinCid = null, encryptedCid = null, cidHash = null,
        gate = null,
        isEncrypted = true,
        encryptionMetadata = GateMetadata.Sealed(
            version = 4,
            encryptedAesKey = "SEALED-$id",
            cid = "bafk-$id",
            chain = "BaseMainnet",
            tokenAddress = token,
            threshold = "5",
            epoch = epoch,
            marketCapTarget = target,
            oracleAddress = oracle,
            attributeGateType = attributeGateType,
        ),
        cidEncryptionMetadata = null,
        attestation = null, arkivStatus = ArkivStatus.FRESH, contentCacheStatus = ContentCacheStatus.UNCACHED,
        lastAccessedAt = null,
    )

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ── Pinned vectors (haven-aol/tests/fixtures/derivation-v4-vectors.json) ───────────────

    @Test
    fun `derivation input matches the canister v4 vectors`() {
        val r = Canister()
        fun d(chain: String, tok: String, thr: Long, epoch: Long, target: Long) = hex(
            r.vetkdDerivationInputV4(chain, tok, BigInteger.valueOf(thr), BigInteger.valueOf(epoch), BigInteger.valueOf(target)),
        )
        assertEquals("481c47e309718312bd013b8c9ef22a64838dd7a923d4203de55be662ac09aeb8", d("BaseMainnet", token, 5, 670, 5_000_000))
        assertEquals(
            "101ced3daa5964741899e760385fec8e22f0d87fbcd6c8db4de9404775cfe00a",
            d("EthMainnet", "0x0000000000000000000000000000000000000001", 1, 671, 1_000_000),
        )
        assertEquals(
            "2441a3575ee465c2bb27a0ede12cba740b89e38d010995899a1527bf59fab86a",
            d("ArbitrumOne", "0x1111111111111111111111111111111111111111", 2, 670, 0),
        )
        assertEquals("c518f628a90769a051a254d3cfd297454ff8b6c218bb86c3dfe7318e3997ab4e", d("BaseMainnet", token, 5, 670, 10_000_000))
    }

    @Test
    fun `threshold zero collapses the v4 epoch like the canister`() {
        val r = Canister()
        val sealed = (v4Item("a").encryptionMetadata as GateMetadata.Sealed).copy(
            chain = "OptimismMainnet",
            tokenAddress = "0x2222222222222222222222222222222222222222",
            threshold = "0",
            epoch = 999,
            marketCapTarget = 10_000_000,
        )
        val inputs = r.epochInputsOrError(sealed, "0xab").getOrThrow()
        assertEquals("7deb7f70cf1f6be7c998226c179ceace6362b0cc58c8027d9128654a628ffb7e", hex(inputs.derivationInput))
        assertEquals(BigInteger.valueOf(999), inputs.requestEpoch)
    }

    @Test
    fun `v4 typed data hashes to the canister's GateRequestV4 typehash`() {
        val json = JSONObject(GateRequestBuilder().buildV4Request("0xab", "0x0102", "670", "10", "42", 8453L))
        val fields = json.getJSONObject("types").getJSONArray("GateRequestV4")
        val encodeType = "GateRequestV4(" + (0 until fields.length()).joinToString(",") {
            val f = fields.getJSONObject(it); "${f.getString("type")} ${f.getString("name")}"
        } + ")"
        assertEquals("b9d5f143468a4d6e11bd1d2ff3eb546445b99a1e871adde2cd2c6008e2980afd", Keccak256.hashHex(encodeType.toByteArray()))
        assertEquals("HavenAOL", json.getJSONObject("domain").getString("name"))
        assertEquals(3, json.getJSONObject("types").getJSONArray("EIP712Domain").length())
        assertEquals("10", json.getJSONObject("message").getString("marketCapTarget"))
    }

    // ── Unlock flow ───────────────────────────────────────────────────────────────────────

    @Test
    fun `reached stage signs GateRequestV4 and calls requestDecryptionKeyV4`() = runBlocking {
        val r = canister(cap = 20)

        val result = r.decrypt(v4Item("a"), r.session)

        assertTrue("got ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(listOf("getMarketCap", "requestDecryptionKeyV4"), r.methods())
        val capArgs = CandidDecoder.decode(r.calls[0].second)
        assertEquals(fieldId("BaseMainnet"), (capArgs[0] as CandidValue.CandidVariant).tag)
        assertEquals(token, (capArgs[1] as CandidValue.CandidText).value)
        assertEquals(bond, (capArgs[2] as CandidValue.CandidText).value)

        val fields = (CandidDecoder.decode(r.calls[1].second).first() as CandidValue.CandidRecord).fields
        assertEquals(
            setOf("chain", "tokenAddress", "threshold", "epoch", "marketCapTarget", "oracleAddress", "evmAddress",
                "transportPublicKey", "nonce", "signature", "eip712ChainId", "eip712VerifyingContract").map { fieldId(it) }.toSet(),
            fields.keys,
        )
        assertEquals(BigInteger.TEN, (fields[fieldId("marketCapTarget")] as CandidValue.CandidNat).value)
        assertEquals(bond, (fields[fieldId("oracleAddress")] as CandidValue.CandidText).value)
        assertEquals(BigInteger.valueOf(8453L), (fields[fieldId("eip712ChainId")] as CandidValue.CandidNat).value)

        val (json, chainId) = r.session.signed.single()
        assertEquals(8453L, chainId)
        assertEquals("GateRequestV4", JSONObject(json).getString("primaryType"))
        assertArrayEquals(
            r.vetkdDerivationInputV4("BaseMainnet", token, BigInteger.valueOf(5), BigInteger.valueOf(670), BigInteger.TEN),
            r.unwrap.seen.single().derivationInput,
        )
    }

    @Test
    fun `locked stage never asks for a signature`() = runBlocking {
        val r = canister(cap = 3)

        val precheck = r.precheck(v4Item("a"))
        val error = r.decrypt(v4Item("a"), r.session).exceptionOrNull()

        assertTrue("$precheck", precheck is HavenError.MarketCapNotReached)
        assertEquals(BigInteger.valueOf(3), (precheck as HavenError.MarketCapNotReached).actual)
        assertTrue("$error", error is HavenError.MarketCapNotReached)
        assertEquals(BigInteger.TEN, (error as HavenError.MarketCapNotReached).required)
        assertTrue("no wallet prompt", r.session.signed.isEmpty())
        assertFalse(r.methods().contains("requestDecryptionKeyV4"))
    }

    @Test
    fun `canister's own MarketCapNotReached maps to the typed error`() = runBlocking {
        // Pre-check unreachable: the signed request goes ahead and the canister decides.
        val r = Canister().also { it.replies["requestDecryptionKeyV4"] = notReachedReply(10, 7) }

        val error = r.decrypt(v4Item("a"), r.session).exceptionOrNull()

        assertTrue("$error", error is HavenError.MarketCapNotReached)
        assertEquals(BigInteger.valueOf(7), (error as HavenError.MarketCapNotReached).actual)
        assertFalse("a refused unlock is not cached", r.hasCachedKey(v4Item("b")))
    }

    @Test
    fun `each drip stage is its own unlock, items of one stage share it`() = runBlocking {
        val r = canister(cap = 100)

        r.decrypt(v4Item("a", target = 10), r.session).getOrThrow()
        r.decrypt(v4Item("b", target = 10), r.session).getOrThrow()
        r.decrypt(v4Item("c", target = 50), r.session).getOrThrow()

        assertEquals("stage 10 once, stage 50 once", 2, r.session.signed.size)
        assertEquals(2, r.methods().count { it == "requestDecryptionKeyV4" })
        assertNull("an unlocked stage needs no pre-check", r.precheck(v4Item("d", target = 10)))
    }

    @Test
    fun `incomplete v4 records fail closed before any call`() = runBlocking {
        val r = canister()

        val noTarget = r.decrypt(v4Item("a", target = null), r.session).exceptionOrNull()
        val noOracle = r.decrypt(v4Item("b", oracle = ""), r.session).exceptionOrNull()

        assertTrue("$noTarget", noTarget is HavenError.UnsupportedGateMetadata)
        assertTrue("$noOracle", noOracle is HavenError.UnsupportedGateMetadata)
        assertTrue(r.calls.isEmpty())
        assertTrue(r.session.signed.isEmpty())
    }

    @Test
    fun `invalid oracle maps to a gate error`() {
        val r = Canister()
        val mapped = r.mapGateError(
            CandidValue.CandidVariant(fieldId("InvalidOracle"), CandidValue.CandidText("oracleAddress must be the chain's Bond contract")),
        )
        assertTrue("$mapped", mapped is HavenError.UnsupportedGateMetadata)
    }

    // ── gate_type attribute vs gate JSON version ─────────────────────────────────────────

    @Test
    fun `gate_type that contradicts the gate version fails closed before any call`() = runBlocking {
        val r = canister()

        val error = r.decrypt(v4Item("a", attributeGateType = 3), r.session).exceptionOrNull()
        val batch = r.decryptAll(listOf(v4Item("b", attributeGateType = 1)), r.session)

        assertTrue("$error", error is HavenError.UnsupportedGateMetadata)
        assertTrue(error!!.message.orEmpty().contains("inconsistent"))
        assertTrue(batch.single().isFailure)
        assertTrue(r.calls.isEmpty())
        assertTrue(r.session.signed.isEmpty())
    }

    @Test
    fun `missing gate_type attribute is not a conflict`() = runBlocking {
        val r = canister()
        assertTrue(r.decrypt(v4Item("a", attributeGateType = null), r.session).isSuccess)
    }
}
