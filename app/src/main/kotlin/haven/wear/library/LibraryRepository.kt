package haven.wear.library

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import haven.mobile.core.arkiv.ArkivClient
import haven.mobile.core.collections.GateAccessChecker
import haven.mobile.core.collections.Holding
import haven.mobile.core.collections.gateKeyOrNull
import haven.mobile.core.domain.HavenChain
import haven.mobile.core.domain.MediaItem
import haven.mobile.core.domain.MediaKind
import haven.mobile.core.domain.TokenGate
import haven.mobile.core.domain.error.HavenError
import haven.mobile.core.haven.aol.HavenAol
import haven.wear.di.AppScope
import haven.wear.wallet.WatchWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The watch's music: Arkiv's gate conditions ∩ what this watch's address holds, audio only.
 *
 * Local-first. The last library is on disk and is what the first frame shows; a refresh replaces it
 * silently when it succeeds and changes nothing when it doesn't. There is no offline mode and no
 * error banner for an unreachable network — what can be played, plays.
 *
 * What goes in (same intersection as haven-mobile's `refreshAccessible`):
 *  - every gate Arkiv knows of where this address holds *something* — enough to open it, or not yet
 *    enough (those tracks show as locked, with how much more is needed);
 *  - drip stages, whose gates usually ask for nothing and so match every holder; they show progress
 *    toward the market-cap target until it opens;
 *  - the address's own uploads.
 */
@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val arkiv: ArkivClient,
    private val access: GateAccessChecker,
    private val wallet: WatchWallet,
    private val havenAol: HavenAol,
    @AppScope private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "library.v1.json")
    private val refreshLock = Mutex()

    private val _library = MutableStateFlow<Library?>(null)
    /** Null only until the snapshot has been read at startup. */
    val library: StateFlow<Library?> = _library.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val snapshot = runCatching { file.takeIf { it.isFile }?.readText()?.let(LibraryCodec::decode) }.getOrNull()
            if (_library.value == null) _library.value = snapshot ?: Library.EMPTY
        }
    }

    fun track(id: String): Track? = _library.value?.track(id)

    /** Refreshes unless the library is younger than [maxAgeMillis]. Never throws. */
    fun refreshIfStale(maxAgeMillis: Long = STALE_AFTER_MILLIS) {
        scope.launch {
            val age = System.currentTimeMillis() - (_library.value?.updatedAtMillis ?: 0L)
            if (age >= maxAgeMillis) refresh()
        }
    }

    /** Rebuilds the library. Returns false if nothing could be reached (the old library stays). */
    suspend fun refresh(): Boolean = refreshLock.withLock {
        _refreshing.value = true
        try {
            val address = wallet.address.value ?: wallet.ensureCreated()
            val built = build(address) ?: return false
            _library.value = built
            withContext(Dispatchers.IO) {
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(LibraryCodec.encode(built))
                tmp.renameTo(file)
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "library refresh failed")
            false
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun build(address: String): Library? = coroutineScope {
        val chains = HavenChain.mainnets.toSet()
        val reached = java.util.concurrent.atomic.AtomicBoolean(false)

        val discovered = arkiv.discoverGates(chains).onSuccess { reached.set(true) }.getOrDefault(emptyList())
        val ownCommunities = arkiv.discoverUserCommunities(address).getOrDefault(emptyList()).map { it.gate }
        val gates = (discovered + ownCommunities).distinctBy { it.gateKeyOrNull() }

        val holdings = if (gates.isEmpty()) emptyMap() else access.holdings(address, gates, chains)

        // A gate is in the library when the address holds any of it, or when it asks for nothing.
        val inScope = gates.filter { gate ->
            val held = gate.gateKeyOrNull()?.let { holdings[it] }
            held != null && (held.balance.signum() > 0 || held.meets(gate.threshold))
        }

        val own = async {
            arkiv.listMediaForOwner(address, PAGE_SIZE, null).onSuccess { reached.set(true) }
                .getOrNull()?.items.orEmpty()
        }
        val fromGates = inScope.map { gate -> async { listAll(gate).also { if (it.isNotEmpty()) reached.set(true) } } }
        val items = (own.await() + fromGates.awaitAll().flatten())
            .filter { it.kind == MediaKind.AUDIO && it.pieceRef != null }
            .distinctBy { it.id }

        if (!reached.get()) return@coroutineScope null

        val gate = Semaphore(PRECHECK_PARALLELISM)
        val tracks = items.map { item ->
            async {
                val key = item.gate?.gateKeyOrNull()
                Track(item, accessFor(item, key?.let { holdings[it] }, gate), key)
            }
        }.awaitAll()

        val titles = holdings.mapNotNull { (key, holding) -> holding.symbol?.let { key to it } }.toMap()
        Library(tracks, titles, System.currentTimeMillis())
    }

    private suspend fun listAll(gate: TokenGate): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = arkiv.listMediaForCommunity(gate, PAGE_SIZE, cursor).getOrNull() ?: break
            out += page.items
            cursor = page.nextCursor
        } while (cursor != null && ++pages < MAX_PAGES_PER_GATE)
        return out
    }

    private suspend fun accessFor(item: MediaItem, holding: Holding?, precheckGate: Semaphore): Access {
        val gate = item.gate
        if (!item.isEncrypted || gate == null) return Access.Open
        // The canister checks the signer's balance even for the creator's own uploads, so only the
        // balance decides. An unreadable balance (null) is not a "no": the unlock decides.
        if (holding != null && !holding.meets(gate.threshold)) {
            return Access.NeedsMore(
                required = BigDecimal.valueOf(gate.threshold).stripTrailingZeros(),
                held = holding.wholeUnits.stripTrailingZeros(),
                symbol = holding.symbol,
            )
        }
        if (item.isDrip) {
            return when (val blocked = precheckGate.withPermit { havenAol.precheck(item) }) {
                is HavenError.MarketCapNotReached -> Access.Drip(blocked.required, blocked.actual)
                else -> Access.Open
            }
        }
        return Access.Open
    }

    /** Called by playback when the canister says otherwise (a drip that fell back, a sold token). */
    fun markLocked(id: String, access: Access) {
        val current = _library.value ?: return
        val updated = current.tracks.map { if (it.id == id) it.copy(access = access) else it }
        _library.value = current.copy(tracks = updated)
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val MAX_PAGES_PER_GATE = 10
        const val PRECHECK_PARALLELISM = 3
        const val STALE_AFTER_MILLIS = 5 * 60 * 1000L
    }
}
