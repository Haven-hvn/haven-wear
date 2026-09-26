package haven.wear.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import haven.wear.library.LibraryRepository
import haven.wear.library.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the Now Playing screen draws. Position is read live from [PlayerConnection.positionMs]. */
data class NowPlaying(
    val mediaId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0L,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val queueIndex: Int = -1,
    /** Waiting for headphones (Wear OS won't play music through the watch speaker). */
    val waitingForOutput: Boolean = false,
) {
    val isEmpty: Boolean get() = mediaId == null
}

data class QueueEntry(val mediaId: String, val title: String, val artist: String)

/**
 * The UI's handle on [PlaybackService]: a `MediaController`, connected once for the process and
 * exposed as plain state + intents. Screens never touch the player directly.
 */
@Singleton
class PlayerConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
) {
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayList<(MediaController) -> Unit>()

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish(player)
            })
            publish(c)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0L

    /** Plays [tracks] from [startIndex]; locked tracks are left out of the queue. */
    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) = withController { c ->
        val lib = library.library.value
        val playable = tracks.filter { it.isPlayable }
        if (playable.isEmpty()) return@withController
        val start = tracks.getOrNull(startIndex)?.let { chosen -> playable.indexOfFirst { it.id == chosen.id } }?.coerceAtLeast(0) ?: 0
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(playable.map { MediaItems.from(it, lib) }, if (shuffle) (playable.indices).random() else start, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    /** Off → all → one → off, like every music app. */
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun skipTo(queueIndex: Int) = withController { if (queueIndex in 0 until it.mediaItemCount) it.seekToDefaultPosition(queueIndex) }

    /** Tile / launcher "resume": continue what was playing, or rebuild the last queue. */
    fun resume() = withController { c ->
        if (c.mediaItemCount == 0) {
            // The service answers with its saved queue via onPlaybackResumption.
            c.prepare()
        }
        c.play()
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: run {
            pending += block
            connect()
        }
    }

    private fun publish(player: Player) {
        val meta: MediaMetadata = player.mediaMetadata
        val current = player.currentMediaItem
        _state.value = NowPlaying(
            mediaId = current?.mediaId,
            title = (meta.title ?: current?.mediaMetadata?.title)?.toString(),
            artist = (meta.artist ?: current?.mediaMetadata?.artist)?.toString(),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            hasPrevious = player.hasPreviousMediaItem() || player.currentPosition > 3_000,
            hasNext = player.hasNextMediaItem(),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            queue = (0 until player.mediaItemCount).map { i ->
                val item = player.getMediaItemAt(i)
                QueueEntry(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty())
            },
            queueIndex = player.currentMediaItemIndex,
            waitingForOutput = player.playWhenReady && !player.isPlaying &&
                player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT,
        )
    }
}
