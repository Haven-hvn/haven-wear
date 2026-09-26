package haven.mobile.core.haven.aol

import haven.mobile.core.crypto.AesKeyCache
import haven.mobile.core.domain.ArkivStatus
import haven.mobile.core.domain.ContentCacheStatus
import haven.mobile.core.domain.GateMetadata
import haven.mobile.core.domain.MediaItem
import haven.mobile.core.domain.MediaKind
import haven.mobile.core.haven.aol.vetkeys.TransportKeypair
import haven.mobile.core.haven.aol.vetkeys.UnwrapParams
import haven.mobile.core.haven.aol.vetkeys.VetKdUnwrap
import haven.mobile.core.wallet.WalletSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * True v1 batch pins: items sharing a gate cost ONE signature and ONE
 * `batchRequestDecryptionKey` call (single EVM check canister-side), with
 * per-cid keys back in input order. Groups on different gates still overlap;
 * one bad item never cancels the rest.
 */
class DecryptAllBatchTest {

    private val transportPub = ByteArray(32) { 0x02 }
    private val transportSecret = ByteArray(32) { 0x03 }

    private open class CannedBatchAol(
        session: WalletSession,
        unwrap: VetKdUnwrap,
        private val cids: List<String>,
    ) : HavenAolImpl(
        HavenAolConfig(canisterId = "gny6k-fqaaa-aaaab-ag3ra-cai", icHost = "https://ic0.app"),
        session,
        AesKeyCache(),
        NonceManager(),
        GateRequestBuilder(),
        unwrap,
    ) {
        val methods = mutableListOf<String>()
        var batchCalls = 0

        override suspend fun callCanister(method: String, candidArg: ByteArray): Result<ByteArray> {
            methods += method
            if (method == "batchRequestDecryptionKey") batchCalls++
            return Result.success(batchReply(cids))
        }

        private fun batchReply(cids: List<String>): ByteArray =
            dev.ic.kotlin.candid.CandidEncoder.encode(
                listOf(
                    dev.ic.kotlin.candid.CandidValue.CandidVariant(
                        dev.ic.kotlin.candid.fieldId("ok"),
                        dev.ic.kotlin.candid.CandidValue.CandidRecord(
                            mapOf(
                                dev.ic.kotlin.candid.fieldId("keys") to
                                    dev.ic.kotlin.candid.CandidValue.CandidVec(
                                        cids.map { cid ->
                                            dev.ic.kotlin.candid.CandidValue.CandidRecord(
                                                mapOf(
                                                    dev.ic.kotlin.candid.fieldId("cid") to
                                                        dev.ic.kotlin.candid.CandidValue.CandidText(cid),
                                                    dev.ic.kotlin.candid.fieldId("encrypted_key") to
                                                        dev.ic.kotlin.candid.CandidValue.CandidBlob(
                                                            ByteArray(32) { 0x04 },
                                                        ),
                                                ),
                                            )
                                        },
                                    ),
                                dev.ic.kotlin.candid.fieldId("verification_key") to
                                    dev.ic.kotlin.candid.CandidValue.CandidBlob(ByteArray(48) { 0x05 }),
                            ),
                        ),
                    ),
                ),
            )
    }

    /** Unwrap echoes the derivation input, so each cid recovers a distinct 32-byte key. */
    private val echoUnwrap = object : VetKdUnwrap {
        override fun isAvailable(): Boolean = true
        override fun generateTransportKeypair(): Result<TransportKeypair> =
            Result.success(TransportKeypair(transportPub, transportSecret))
        override fun unwrapContentKey(params: UnwrapParams): Result<ByteArray> =
            Result.success(params.derivationInput.copyOf())
    }

    private class SignTracker(val signDelayMs: Long = 100) {
        val signs = AtomicInteger(0)
        val current = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val chainIds = java.util.Collections.synchronizedList(mutableListOf<Long>())
    }

    private fun trackingSession(tracker: SignTracker) = object : WalletSession {
        override val address = MutableStateFlow<String?>("0xabc")
        override val diagnostics = MutableStateFlow<List<String>>(emptyList())
        override val pairingUri = MutableStateFlow<String?>(null)
        override suspend fun connect(): Result<String> = Result.success("0xabc")
        override suspend fun disconnect() = Unit
        override suspend fun signTypedDataV4(json: String, chainId: Long): Result<String> {
            tracker.signs.incrementAndGet()
            tracker.chainIds += chainId
            val now = tracker.current.incrementAndGet()
            tracker.maxObserved.updateAndGet { m -> maxOf(m, now) }
            try {
                delay(tracker.signDelayMs)
            } finally {
                tracker.current.decrementAndGet()
            }
            return Result.success("0x" + "11".repeat(65))
        }
        override suspend fun sendTransaction(
            to: String,
            data: String,
            chainId: Long,
            valueHex: String,
        ): Result<String> = Result.failure(IllegalStateException("must not be reached"))
    }

    private fun sealedItem(id: String, cid: String, tokenAddress: String = "0xtoken") = MediaItem(
        id = id, kind = MediaKind.VIDEO, owner = "0xabc", title = "sealed-$id", description = null,
        mimeType = "video/mp4", fileExtension = ".mp4", filenameHint = null, sizeBytes = null,
        createdAt = Instant.fromEpochMilliseconds(0), createdAtBlock = 100, expiresAtBlock = null,
        pieceRef = null, filecoinCid = null, encryptedCid = null, cidHash = null,
        gate = null,
        isEncrypted = true,
        encryptionMetadata = GateMetadata.Sealed(
            version = 1,
            encryptedAesKey = "U0VBTElORw==",
            cid = cid,
            chain = "EthSepolia",
            tokenAddress = tokenAddress,
            threshold = "1",
        ),
        cidEncryptionMetadata = null,
        attestation = null, arkivStatus = ArkivStatus.FRESH, contentCacheStatus = ContentCacheStatus.UNCACHED,
        lastAccessedAt = null,
    )

    private fun expectedKey(cid: String): ByteArray {
        val preimage = "accessol:EthSepolia:0xtoken:1:$cid".toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.getInstance("SHA-256").digest(preimage)
    }

    @org.junit.Test
    fun `same gate shares one signature and one batch call`() {
        runBlocking {
            val tracker = SignTracker(signDelayMs = 0)
            val session = trackingSession(tracker)
            val cids = listOf("sha256:one", "sha256:two", "sha256:three")
            val impl = CannedBatchAol(session, echoUnwrap, cids)
            val items = cids.mapIndexed { i, cid -> sealedItem("item-$i", cid) }

            val results = impl.decryptAll(items, session)

            assertEquals(listOf("batchRequestDecryptionKey"), impl.methods)
            assertEquals(1, impl.batchCalls)
            assertEquals(1, tracker.signs.get())
            // EthSepolia gates sign a Sepolia domain over a Sepolia request.
            assertEquals(listOf(11155111L), tracker.chainIds.toList())
            assertEquals(3, results.size)
            results.forEachIndexed { i, result ->
                assertTrue("item $i should succeed", result.isSuccess)
                assertArrayEquals("item $i key must match its own cid", expectedKey(cids[i]), result.getOrNull())
            }
        }
    }

    @org.junit.Test
    fun `different gates serialize signatures with one each`() {
        runBlocking {
            val tracker = SignTracker(signDelayMs = 100)
            val session = trackingSession(tracker)
            val cids = listOf("sha256:a", "sha256:b")
            val impl = CannedBatchAol(session, echoUnwrap, cids)
            val items = listOf(
                sealedItem("item-a", "sha256:a", tokenAddress = "0xtokenA"),
                sealedItem("item-b", "sha256:b", tokenAddress = "0xtokenB"),
            )

            val results = impl.decryptAll(items, session)

            assertTrue(results.all { it.isSuccess })
            assertEquals(2, tracker.signs.get())
            assertEquals(2, impl.batchCalls)
            // The wallet stack serves one request pipeline behind a single global
            // delegate: overlapping eth_signTypedData_v4 calls corrupt each other
            // and kill the process on device (bulk unlock of different-gate items).
            // Groups still run together — only the signature itself is exclusive.
            assertEquals(
                "signatures must never overlap, max was ${tracker.maxObserved.get()}",
                1,
                tracker.maxObserved.get(),
            )
        }
    }

    @org.junit.Test
    fun `one bad item does not cancel the rest`() {
        runBlocking {
            val tracker = SignTracker(signDelayMs = 0)
            val session = trackingSession(tracker)
            val impl = CannedBatchAol(session, echoUnwrap, listOf("sha256:good"))
            val good = sealedItem("good", "sha256:good")
            // Blank seal cid fails the batch precondition, then fails closed on the
            // single path before signing — the batch must still deliver the good item.
            val bad = sealedItem("bad", "").copy(
                encryptionMetadata = GateMetadata.Sealed(
                    version = 1,
                    encryptedAesKey = "U0VBTElORw==",
                    cid = "",
                    chain = "EthSepolia",
                    tokenAddress = "0xtoken",
                    threshold = "1",
                ),
            )

            val results = impl.decryptAll(listOf(good, bad), session)

            assertEquals(2, results.size)
            assertTrue(results[0].isSuccess)
            assertTrue(results[1].isFailure)
            assertEquals(1, tracker.signs.get())
        }
    }

    @org.junit.Test
    fun `commitment is deterministic and order-sensitive`() {
        val impl = CannedBatchAol(
            trackingSession(SignTracker(signDelayMs = 0)),
            echoUnwrap,
            emptyList(),
        )
        val a = impl.batchCidsCommitmentHex("EthSepolia", "0xtoken", "1", listOf("sha256:one", "sha256:two"))
        val b = impl.batchCidsCommitmentHex("EthSepolia", "0xtoken", "1", listOf("sha256:one", "sha256:two"))
        val c = impl.batchCidsCommitmentHex("EthSepolia", "0xtoken", "1", listOf("sha256:two", "sha256:one"))
        assertEquals(a, b)
        assertNotEquals("submitted order commits", a, c)
        assertTrue(a.startsWith("0x"))
        assertEquals(66, a.length)
    }

    @org.junit.Test
    fun `chain resolver follows the gate with dapp fallback`() {
        assertEquals(11155111L, eip155ForChainVariant("EthSepolia"))
        assertEquals(1L, eip155ForChainVariant("EthMainnet"))
        assertEquals(1L, eip155ForChainVariant("NoSuchChain"))
    }

    @org.junit.Test
    fun `empty batch returns empty without signing`() {
        runBlocking {
            val tracker = SignTracker(signDelayMs = 0)
            val session = trackingSession(tracker)
            val impl = CannedBatchAol(session, echoUnwrap, emptyList())

            assertTrue(impl.decryptAll(emptyList(), session).isEmpty())
            assertEquals(0, tracker.signs.get())
            assertTrue(impl.methods.isEmpty())
        }
    }
}
