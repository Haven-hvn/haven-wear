package haven.wear.library

import android.content.Context
import cloud.filecoin.foc.cache.PieceRef
import haven.mobile.core.arkiv.ArkivClient
import haven.mobile.core.arkiv.ArkivPage
import haven.mobile.core.collections.GateAccessChecker
import haven.mobile.core.collections.Holding
import haven.mobile.core.collections.gateKeyOrNull
import haven.mobile.core.domain.ArkivStatus
import haven.mobile.core.domain.Community
import haven.mobile.core.domain.ContentCacheStatus
import haven.mobile.core.domain.GateMetadata
import haven.mobile.core.domain.HavenChain
import haven.mobile.core.domain.LaunchStage
import haven.mobile.core.domain.MediaItem
import haven.mobile.core.domain.MediaKind
import haven.mobile.core.domain.TokenGate
import haven.mobile.core.domain.error.HavenError
import haven.mobile.core.haven.aol.HavenAol
import haven.mobile.core.wallet.WalletSession
import haven.wear.wallet.WatchWallet
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigInteger

/**
 * The library intersection with every network dependency faked (no sockets, no canister): what
 * shows, what's locked and why, and that an unreachable network leaves the last library alone.
 */
class LibraryRepositoryTest {

    @TempDir lateinit var dir: File

    private val me = "0x9858effd232b4033e47d90003d41ec34ecaeda94"
    private val held = TokenGate("BaseMainnet", "0x" + "aa".repeat(20), 1.0)
    private val notHeld = TokenGate("BaseMainnet", "0x" + "bb".repeat(20), 1.0)
    private val dripGate = TokenGate("BaseMainnet", "0x" + "cc".repeat(20), 0.0)

    private fun media(id: String, gate: TokenGate?, kind: MediaKind = MediaKind.AUDIO, threshold: Double? = null, sealed: GateMetadata? = null) = MediaItem(
        id = id, kind = kind, owner = "0x" + "ee".repeat(20), title = id, description = null, mimeType = "audio/mpeg",
        fileExtension = "mp3", filenameHint = null, sizeBytes = 1000, createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        createdAtBlock = null, expiresAtBlock = null, pieceRef = PieceRef(pieceCid = "baga$id", size = 1000, providerServiceUrls = emptyList()),
        filecoinCid = null, encryptedCid = null, cidHash = null,
        gate = gate?.let { if (threshold != null) it.copy(threshold = threshold) else it },
        isEncrypted = gate != null, encryptionMetadata = sealed, cidEncryptionMetadata = null, attestation = null,
        arkivStatus = ArkivStatus.FRESH, contentCacheStatus = ContentCacheStatus.UNCACHED, lastAccessedAt = null,
    )

    private val dripSeal = GateMetadata.Sealed(version = 4, encryptedAesKey = "k", chain = "BaseMainnet", tokenAddress = dripGate.tokenAddress, threshold = "0", epoch = 1, marketCapTarget = 5, oracleAddress = "0x" + "dd".repeat(20))

    private inner class FakeArkiv(val reachable: Boolean = true) : ArkivClient {
        val byGate = mapOf(
            held.gateKeyOrNull() to listOf(media("open", held), media("pricey", held, threshold = 10.0), media("clip", held, MediaKind.VIDEO)),
            notHeld.gateKeyOrNull() to listOf(media("never", notHeld)),
            dripGate.gateKeyOrNull() to listOf(media("premiere", dripGate, sealed = dripSeal)),
        )
        private fun <T> maybe(value: T): Result<T> = if (reachable) Result.success(value) else Result.failure(HavenError.NetworkError("down"))
        override suspend fun listMediaForOwner(owner: String, pageSize: Int, cursor: String?) = maybe(ArkivPage(listOf(media("mine", null)), null))
        override suspend fun listMediaForCommunity(gate: TokenGate, pageSize: Int, cursor: String?) = maybe(ArkivPage(byGate[gate.gateKeyOrNull()].orEmpty(), null))
        override suspend fun discoverGates(chains: Set<HavenChain>) = maybe(listOf(held, notHeld, dripGate))
        override suspend fun discoverUserCommunities(address: String) = maybe(emptyList<Community>())
        override suspend fun listLaunches() = maybe(emptyList<LaunchStage>())
        override suspend fun getMedia(id: String) = maybe<MediaItem?>(null)
    }

    private val checker = object : GateAccessChecker {
        override suspend fun satisfied(walletAddress: String, gates: List<TokenGate>, chains: Set<HavenChain>) = emptySet<String>()
        override suspend fun holdings(walletAddress: String, gates: List<TokenGate>, chains: Set<HavenChain>) = mapOf(
            held.gateKeyOrNull()!! to Holding(BigInteger.valueOf(3), null, "HVN"),
            notHeld.gateKeyOrNull()!! to Holding(BigInteger.ZERO, null, "NOPE"),
            dripGate.gateKeyOrNull()!! to Holding(BigInteger.ZERO, 18, "DRIP"),
        )
    }

    private val aol = object : HavenAol {
        override suspend fun decrypt(item: MediaItem, session: WalletSession) = Result.failure<ByteArray>(IllegalStateException("not in this test"))
        override suspend fun hasCachedKey(item: MediaItem) = false
        override suspend fun precheck(item: MediaItem): HavenError? =
            if (item.id == "premiere") HavenError.MarketCapNotReached(BigInteger.valueOf(5), BigInteger.valueOf(2)) else null
        override suspend fun decryptAll(items: List<MediaItem>, session: WalletSession, onProgress: suspend (Int, Int) -> Unit) = emptyList<Result<ByteArray>>()
        override val canisterId = "gny6k-fqaaa-aaaab-ag3ra-cai"
        override suspend fun verificationKey() = Result.failure<ByteArray>(IllegalStateException())
        override suspend fun attestationPublicKey() = Result.failure<ByteArray>(IllegalStateException())
        override suspend fun clearFor(walletAddress: String) = Unit
    }

    private fun repo(arkiv: ArkivClient): LibraryRepository {
        val context = mockk<Context> { every { filesDir } returns dir }
        val wallet = mockk<WatchWallet> { every { address } returns MutableStateFlow(me) }
        return LibraryRepository(context, arkiv, checker, wallet, aol, CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun `library is the intersection, audio only, with reasons for what is locked`() = runBlocking {
        val repo = repo(FakeArkiv())
        assertTrue(repo.refresh())
        val lib = repo.library.value!!

        assertEquals(setOf("open", "pricey", "premiere", "mine"), lib.tracks.map { it.id }.toSet())
        assertEquals(Access.Open, lib.track("open")!!.access)
        assertEquals(Access.Open, lib.track("mine")!!.access)
        val pricey = lib.track("pricey")!!.access as Access.NeedsMore
        assertEquals(0, pricey.required.compareTo(java.math.BigDecimal.TEN))
        assertEquals(0, pricey.held.compareTo(java.math.BigDecimal(3)))
        assertEquals("HVN", pricey.symbol)
        assertEquals(Access.Drip(BigInteger.valueOf(5), BigInteger.valueOf(2)), lib.track("premiere")!!.access)
        assertEquals("HVN", lib.collections.first { it.key == held.gateKeyOrNull() }.title)

        assertTrue(File(dir, "library.v1.json").isFile)
    }

    @Test
    fun `an unreachable network keeps the last library`() = runBlocking {
        repo(FakeArkiv()).refresh()
        val offline = repo(FakeArkiv(reachable = false))
        // The snapshot is loaded on init; a failed refresh must not replace it.
        assertFalse(offline.refresh())
        kotlinx.coroutines.delay(50)
        assertEquals(4, offline.library.value?.tracks?.size)
    }
}
