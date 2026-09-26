package haven.wear.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import cloud.filecoin.foc.cache.PieceTransform
import dagger.hilt.android.qualifiers.ApplicationContext
import haven.mobile.core.cache.HavenCache
import haven.mobile.core.crypto.HavenCipher
import haven.mobile.core.crypto.stripCarContainer
import haven.mobile.core.domain.MediaItem
import haven.mobile.core.domain.error.HavenError
import haven.mobile.core.haven.aol.HavenAol
import haven.mobile.core.wallet.WalletSession
import haven.wear.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A track as a playable file on the watch. foc does all the storage work — fetching, keeping, sizing
 * and evicting — for open and gated tracks alike; this class only says how a gated piece is stored.
 *
 * Gated tracks are fetched with [decryptOnce]: on first play (or prefetch) the watch gets the key
 * from the canister in one silent signed request (v3/v4 gates share one per epoch or drip stage),
 * and foc keeps the decrypted file under the piece's CID. Open tracks are kept as retrieved. Every
 * play after that, restarts included, is a local file read: no network, no canister, no key.
 */
@Singleton
class TrackFiles @Inject constructor(
    @ApplicationContext context: Context,
    private val havenAol: HavenAol,
    private val wallet: WalletSession,
    private val cache: HavenCache,
    private val cipher: HavenCipher,
    private val artwork: ArtworkStore,
    @AppScope private val scope: CoroutineScope,
) {
    /** Small covers pulled from each track's embedded picture. Pruned along with evicted tracks. */
    private val artDir = File(context.filesDir, "art").apply { mkdirs() }

    /** One background fetch at a time: prefetch must never compete with the track that's playing. */
    private val prefetchPermit = Semaphore(1)

    fun artFile(pieceCid: String): File = File(artDir, "$pieceCid.jpg")

    /** The playable file for [item], fetching and (for gated tracks) decrypting it first if needed. */
    suspend fun file(item: MediaItem): File = withContext(Dispatchers.IO) {
        val piece = item.pieceRef ?: throw HavenError.CacheMiss("This track has no stored content.")
        val transform = if (item.isEncrypted) decryptOnce(item) else null
        val file = cache.file(piece, transform).getOrThrow()
        if (!artFile(piece.pieceCid).isFile) saveCover(item, file)
        file
    }

    /** Gets the next few queued tracks onto the watch before they're needed. */
    fun prefetch(items: List<MediaItem>) {
        items.forEach { item ->
            scope.launch {
                val cid = item.pieceRef?.pieceCid ?: return@launch
                if (cache.exists(cid)) return@launch
                prefetchPermit.withPermit { runCatching { file(item) } }
            }
        }
    }

    /** Drops covers whose track foc has evicted. */
    suspend fun pruneArt() = withContext(Dispatchers.IO) {
        artDir.listFiles()?.forEach { f ->
            if (!cache.exists(f.nameWithoutExtension)) f.delete()
        }
    }

    /**
     * Stored form of a gated piece: CAR wrapper stripped, decrypted. Only called by foc when the piece
     * isn't on the watch yet, so the canister is asked for a key at most once per piece. A wrong key
     * or a refused unlock throws, and foc stores nothing.
     */
    private fun decryptOnce(item: MediaItem) = PieceTransform { _, input, output ->
        val key = havenAol.decrypt(item, wallet).getOrThrow()
        val ciphertext = flow {
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                emit(buf.copyOf(n))
            }
        }
        var written = 0L
        cipher.decryptStream(key, ciphertext.stripCarContainer(), null).collect { chunk ->
            written += chunk.size
            if (written > MAX_TRACK_BYTES) throw HavenError.PlaybackStreamError("This track is too large for a watch.")
            output.write(chunk)
        }
    }

    /** Embedded cover → a small image, so the library shows it before playback. */
    private fun saveCover(item: MediaItem, audio: File) {
        val cid = item.pieceRef?.pieceCid ?: return
        val picture = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(audio.path)
                    embeddedPicture
                } finally {
                    release()
                }
            }
        }.getOrNull() ?: return
        artwork.save(item.id, picture, artFile(cid))
    }

    private companion object {
        const val CHUNK = 256 * 1024
        const val MAX_TRACK_BYTES = 512L * 1024 * 1024
    }
}
