package haven.wear.library

import haven.mobile.core.domain.GateMetadata
import haven.mobile.core.domain.HavenChain
import haven.mobile.core.domain.MediaItem
import java.math.BigDecimal
import java.math.BigInteger

/** One playable-or-not song, with everything the library screens need to decide how to show it. */
data class Track(
    val item: MediaItem,
    val access: Access,
    /** Gate key (`chain:token`) of the collection it belongs to; null for ungated items. */
    val collectionKey: String?,
) {
    val id: String get() = item.id
    val title: String get() = item.title.ifBlank { "Untitled" }
    val artistKey: String get() =
        (item.creatorHandle?.takeIf { it.isNotBlank() } ?: item.creatorAddress ?: item.owner).lowercase()
    val artistName: String get() = item.creatorHandle?.takeIf { it.isNotBlank() } ?: shortAddress(item.owner)
    val pieceCid: String? get() = item.pieceRef?.pieceCid
    val isPlayable: Boolean get() = access is Access.Open
}

/**
 * Whether this watch can open a track right now. Decided from public data (balances, the canister's
 * market-cap read) so the library can say "not yet" without asking the canister for a key it would
 * refuse. The signed unlock remains the authority: [Open] is a prediction, not a promise.
 */
sealed interface Access {
    data object Open : Access

    /** Below the gate's threshold. Amounts are whole units (tokens, or items for a collection). */
    data class NeedsMore(val required: BigDecimal, val held: BigDecimal, val symbol: String?) : Access

    /** A drip stage: opens when the token's market cap reaches [required] whole reserve units (ETH). */
    data class Drip(val required: BigInteger, val actual: BigInteger?) : Access {
        val progress: Float
            get() = when {
                actual == null || required.signum() <= 0 -> 0f
                else -> (actual.toDouble() / required.toDouble()).coerceIn(0.0, 1.0).toFloat()
            }
    }
}

data class TrackCollection(
    val key: String,
    val title: String,
    val chain: HavenChain?,
    val tracks: List<Track>,
) {
    val playableCount: Int get() = tracks.count { it.isPlayable }
}

data class Artist(val key: String, val name: String, val tracks: List<Track>)

data class Library(
    val tracks: List<Track>,
    val collectionTitles: Map<String, String>,
    val updatedAtMillis: Long,
) {
    private val byId: Map<String, Track> = tracks.associateBy { it.id }

    fun track(id: String): Track? = byId[id]

    val collections: List<TrackCollection> by lazy {
        tracks.filter { it.collectionKey != null }
            .groupBy { it.collectionKey!! }
            .map { (key, list) ->
                TrackCollection(
                    key = key,
                    title = collectionTitles[key] ?: defaultCollectionTitle(key),
                    chain = HavenChain.entries.firstOrNull { key.startsWith(it.aolVariant + ":") },
                    tracks = list.sortedWith(trackOrder),
                )
            }
            .sortedWith(compareByDescending<TrackCollection> { it.playableCount > 0 }.thenBy { it.title.lowercase() })
    }

    val artists: List<Artist> by lazy {
        tracks.groupBy { it.artistKey }
            .map { (key, list) -> Artist(key, list.first().artistName, list.sortedWith(trackOrder)) }
            .sortedBy { it.name.lowercase() }
    }

    val songs: List<Track> by lazy { tracks.sortedWith(trackOrder) }

    companion object {
        val EMPTY = Library(emptyList(), emptyMap(), 0L)

        /** Playable first, then title — a locked song is still worth seeing, just not first. */
        private val trackOrder = compareByDescending<Track> { it.isPlayable }.thenBy { it.title.lowercase() }
    }
}

fun shortAddress(address: String): String {
    val a = address.trim()
    return if (a.length > 12 && a.startsWith("0x")) "${a.take(6)}…${a.takeLast(4)}" else a
}

private fun defaultCollectionTitle(key: String): String = shortAddress(key.substringAfter(':'))

/** True for sealed v4 drip stages. */
internal val MediaItem.isDrip: Boolean
    get() = (encryptionMetadata as? GateMetadata.Sealed)?.isMarketCapDrip == true
