package haven.wear.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import haven.wear.library.Library
import haven.wear.library.LibraryRepository
import haven.wear.library.Track
import haven.wear.playback.ArtworkStore
import haven.wear.playback.AudioChapter
import haven.wear.playback.NowPlaying
import haven.wear.playback.PlayerConnection
import haven.wear.playback.TrackFiles
import haven.wear.playback.readId3Chapters
import haven.wear.wallet.WatchWallet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The whole app's state in one place — library, art, what's playing, the address — because on a
 * watch every screen is two taps from every other and they all show the same few things.
 */
@HiltViewModel
class HavenViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val libraryRepository: LibraryRepository,
    private val player: PlayerConnection,
    private val wallet: WatchWallet,
    private val artworkStore: ArtworkStore,
    private val trackFiles: TrackFiles,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    val library: StateFlow<Library?> = libraryRepository.library
    val refreshing: StateFlow<Boolean> = libraryRepository.refreshing
    val art: StateFlow<Map<String, Bitmap>> = artworkStore.art
    val nowPlaying: StateFlow<NowPlaying> = player.state
    val address: StateFlow<String?> = wallet.address

    private val _setupDone = MutableStateFlow(prefs.getBoolean(KEY_SETUP_DONE, false))
    val setupDone: StateFlow<Boolean> = _setupDone.asStateFlow()

    val hasAnything: StateFlow<Boolean> = library
        .map { !it?.tracks.isNullOrEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val positionMs: Long get() = player.positionMs

    /**
     * Cue points inside the playing track: merged single-file albums carry one ID3 chapter
     * per song, parsed from the local file (already on the watch — it is playing). Empty
     * for ordinary tracks, so the chapters entry only appears where it leads somewhere.
     */
    private val _chapters = MutableStateFlow<List<AudioChapter>>(emptyList())
    val chapters: StateFlow<List<AudioChapter>> = _chapters.asStateFlow()

    init {
        // Covers saved beside tracks already on the watch come back after a restart.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            trackFiles.pruneArt()
            library.collect { lib ->
                artworkStore.load(
                    lib?.tracks.orEmpty().mapNotNull { t -> t.pieceCid?.let { t.id to trackFiles.artFile(it) } },
                )
            }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            player.state.map { it.mediaId }.distinctUntilChanged().collect { id ->
                val track = id?.let { libraryRepository.library.value?.track(it) }
                _chapters.value = track?.let {
                    runCatching { readId3Chapters(trackFiles.file(it.item)) }.getOrDefault(emptyList())
                } ?: emptyList()
            }
        }
    }

    fun finishSetup() {
        prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
        _setupDone.value = true
        viewModelScope.launch { libraryRepository.refresh() }
    }

    fun refresh() {
        viewModelScope.launch { libraryRepository.refresh() }
    }

    /** Bumped whenever music is chosen, so Home turns to Now Playing. */
    private val _showPlayer = MutableStateFlow(0)
    val showPlayer: StateFlow<Int> = _showPlayer.asStateFlow()

    fun play(tracks: List<Track>, start: Track? = null) {
        player.play(tracks, startIndex = start?.let { tracks.indexOf(it) }?.coerceAtLeast(0) ?: 0)
        _showPlayer.value += 1
    }

    fun shuffle(tracks: List<Track>) {
        player.play(tracks, shuffle = true)
        _showPlayer.value += 1
    }

    suspend fun recoveryWords(): List<String> = wallet.recoveryWords()

    fun togglePlay() = player.togglePlay()
    fun next() = player.next()
    fun previous() = player.previous()
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
    fun skipTo(index: Int) = player.skipTo(index)
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    private companion object {
        const val KEY_SETUP_DONE = "setup_done"
    }
}
