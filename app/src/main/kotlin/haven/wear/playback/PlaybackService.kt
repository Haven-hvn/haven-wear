package haven.wear.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import haven.mobile.core.domain.error.HavenError
import haven.wear.MainActivity
import haven.wear.library.Access
import haven.wear.library.LibraryRepository
import haven.wear.tile.ResumeTileService
import javax.inject.Inject

/**
 * Playback lives here, not in a screen: music keeps going with the app closed, and the session is
 * what the system media controls, the watch face complication and the Tile talk to.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var dataSourceFactory: HavenDataSource.Factory
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var files: TrackFiles
    @Inject lateinit var lastPlayed: LastPlayedStore

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Wear OS: never blare out of the watch speaker. With no headphones connected, playback
            // waits and the system output switcher opens so the listener can pick their earbuds.
            .setSuppressPlaybackOnUnsuitableOutput(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(PlayerEvents(player))

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveQueue()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun saveQueue() {
        val player = session?.player ?: return
        if (player.mediaItemCount == 0) return
        val current = player.currentMediaItem ?: return
        lastPlayed.save(
            LastPlayedStore.LastPlayed(
                queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition,
                title = current.mediaMetadata.title?.toString().orEmpty(),
                artist = current.mediaMetadata.artist?.toString().orEmpty(),
            ),
        )
        TileService.getUpdater(this).requestUpdate(ResumeTileService::class.java)
    }

    private inner class PlayerEvents(private val player: Player) : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveQueue()
            // Decrypt the next few songs onto the watch before they're needed.
            val next = (1..PREFETCH_AHEAD).mapNotNull { offset ->
                val index = player.currentMediaItemIndex + offset
                if (index < player.mediaItemCount) library.track(player.getMediaItemAt(index).mediaId)?.item else null
            }
            files.prefetch(next)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveQueue()
        }

        /**
         * A track the library thought was open turned out not to be (a drip fell back under its
         * target, the token was sold). Mark it locked and move on, rather than stopping the music.
         */
        override fun onPlayerError(error: PlaybackException) {
            val reason = generateSequence(error as Throwable) { it.cause }
                .filterIsInstance<TrackUnavailableException>().firstOrNull()?.reason
            val id = player.currentMediaItem?.mediaId
            if (id != null && reason is HavenError.MarketCapNotReached) {
                library.markLocked(id, Access.Drip(reason.required, reason.actual))
            }
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(MediaItems.resolve(mediaItems, library.library.value).toMutableList())

        /** "Resume" from the system media controls after the process was gone. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = lastPlayed.read()
            val items = saved?.queue.orEmpty().map { MediaItem.Builder().setMediaId(it).build() }
            val resolved = MediaItems.resolve(items, library.library.value)
            if (saved == null || resolved.isEmpty()) {
                return Futures.immediateFailedFuture(UnsupportedOperationException("nothing to resume"))
            }
            val index = saved.index.coerceIn(0, resolved.lastIndex)
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(resolved, index, saved.positionMs))
        }
    }

    private companion object {
        const val PREFETCH_AHEAD = 3
    }
}
