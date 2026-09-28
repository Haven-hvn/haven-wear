package haven.wear

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.audio.ui.VolumeViewModel
import com.google.android.horologist.audio.ui.material3.VolumeScreen
import com.google.android.horologist.audio.ui.volumeRotaryBehavior
import com.google.android.horologist.media.ui.material3.screens.playerlibrarypager.PlayerLibraryPagerScreen
import haven.wear.ui.HavenViewModel
import haven.wear.ui.library.ArtistsScreen
import haven.wear.ui.library.CollectionsScreen
import haven.wear.ui.library.LibraryHome
import haven.wear.ui.library.TrackListScreen
import haven.wear.ui.locked.LockedScreen
import haven.wear.ui.player.ChaptersScreen
import haven.wear.ui.player.NowPlayingScreen
import haven.wear.ui.player.QueueScreen
import haven.wear.ui.settings.RecoveryPhraseScreen
import haven.wear.ui.settings.SettingsScreen
import haven.wear.ui.setup.AddressScreen
import haven.wear.ui.theme.HavenTheme
import kotlinx.coroutines.flow.StateFlow

private object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val VOLUME = "volume"
    const val QUEUE = "queue"
    const val CHAPTERS = "chapters"
    const val COLLECTIONS = "collections"
    const val COLLECTION = "collection/{key}"
    const val ARTISTS = "artists"
    const val ARTIST = "artist/{key}"
    const val SONGS = "songs"
    const val LOCKED = "locked/{id}"
    const val SETTINGS = "settings"
    const val ADDRESS = "address"
    const val RECOVERY = "recovery"

    fun collection(key: String) = "collection/${Uri.encode(key)}"
    fun artist(key: String) = "artist/${Uri.encode(key)}"
    fun locked(id: String) = "locked/${Uri.encode(id)}"
}

/**
 * Structure from Jetcaster's Wear app: a swipe-to-dismiss nav host whose root is Horologist's
 * player/library pager. Swipe left from Now Playing for the library; the crown is volume.
 */
@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearApp(navController: NavHostController, resumeRequests: StateFlow<Int>) {
    val vm: HavenViewModel = hiltViewModel()
    val volumeViewModel: VolumeViewModel = viewModel(factory = VolumeViewModel.Factory)

    val setupDone by vm.setupDone.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val art by vm.art.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val address by vm.address.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val resume by resumeRequests.collectAsStateWithLifecycle()
    val showPlayer by vm.showPlayer.collectAsStateWithLifecycle()

    HavenTheme {
        AppScaffold {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = if (setupDone) Routes.HOME else Routes.SETUP,
                state = rememberSwipeDismissableNavHostState(),
                modifier = Modifier.background(Color.Black),
            ) {
                composable(Routes.SETUP) {
                    AddressScreen(
                        address = address,
                        onDone = {
                            vm.finishSetup()
                            navController.navigate(Routes.HOME) { popUpTo(Routes.SETUP) { inclusive = true } }
                        },
                    )
                }

                composable(Routes.HOME) { backStack ->
                    val volumeState by volumeViewModel.volumeUiState.collectAsStateWithLifecycle()
                    // Open on the library when nothing is playing; on Now Playing when something is.
                    val pagerState = rememberPagerState(initialPage = if (nowPlaying.isEmpty) 1 else 0, pageCount = { 2 })
                    // Turn to Now Playing once per new "music chosen" / Tile-resume signal — not every
                    // time Home is shown again after browsing.
                    var handled by rememberSaveable { mutableIntStateOf(0) }
                    LaunchedEffect(resume + showPlayer) {
                        val signal = resume + showPlayer
                        if (signal > handled) {
                            handled = signal
                            pagerState.animateScrollToPage(0)
                        }
                    }
                    val focus = remember { FocusRequester() }

                    PlayerLibraryPagerScreen(
                        pagerState = pagerState,
                        volumeUiState = { volumeState },
                        displayVolumeIndicatorEvents = volumeViewModel.displayIndicatorEvents,
                        playerScreen = {
                            NowPlayingScreen(
                                state = nowPlaying,
                                art = nowPlaying.mediaId?.let { art[it] },
                                positionMs = { vm.positionMs },
                                onTogglePlay = vm::togglePlay,
                                onNext = vm::next,
                                onPrevious = vm::previous,
                                onShuffle = vm::toggleShuffle,
                                onRepeat = vm::cycleRepeat,
                                onQueue = { navController.navigate(Routes.QUEUE) },
                                chapters = chapters,
                                onChapters = { navController.navigate(Routes.CHAPTERS) },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .requestFocusOnHierarchyActive()
                                    .rotaryScrollable(
                                        volumeRotaryBehavior(
                                            volumeUiStateProvider = { volumeState },
                                            onRotaryVolumeInput = { volumeViewModel.setVolume(it) },
                                        ),
                                        focusRequester = focus,
                                    ),
                            )
                        },
                        libraryScreen = {
                            LibraryHome(
                                library = library,
                                art = art,
                                currentId = nowPlaying.mediaId,
                                onShuffleAll = { library?.let { vm.shuffle(it.songs) } },
                                onCollections = { navController.navigate(Routes.COLLECTIONS) },
                                onArtists = { navController.navigate(Routes.ARTISTS) },
                                onSongs = { navController.navigate(Routes.SONGS) },
                                onTrack = { track ->
                                    if (track.isPlayable) {
                                        val recent = library?.tracks?.sortedByDescending { it.item.createdAt }.orEmpty()
                                        vm.play(recent, track)
                                    } else {
                                        navController.navigate(Routes.locked(track.id))
                                    }
                                },
                                onSettings = { navController.navigate(Routes.SETTINGS) },
                                onShowAddress = { navController.navigate(Routes.ADDRESS) },
                            )
                        },
                        backStack = backStack,
                    )
                }

                composable(Routes.VOLUME) {
                    ScreenScaffold(timeText = {}) { VolumeScreen(volumeViewModel = volumeViewModel) }
                }

                composable(Routes.QUEUE) {
                    QueueScreen(state = nowPlaying, art = art, onSkipTo = { index ->
                        vm.skipTo(index)
                        navController.popBackStack()
                    })
                }

                composable(Routes.CHAPTERS) {
                    ChaptersScreen(
                        chapters = chapters,
                        positionMs = { vm.positionMs },
                        isPlaying = nowPlaying.isPlaying,
                        onSeekTo = { startMs ->
                            vm.seekTo(startMs)
                            navController.popBackStack()
                        },
                    )
                }

                composable(Routes.COLLECTIONS) {
                    CollectionsScreen(library?.collections.orEmpty(), art) { navController.navigate(Routes.collection(it.key)) }
                }
                composable(Routes.COLLECTION) { entry ->
                    val key = entry.arguments?.getString("key")?.let(Uri::decode)
                    val collection = library?.collections?.firstOrNull { it.key == key }
                    if (collection != null) {
                        TrackListScreen(
                            title = collection.title,
                            subtitle = collection.chain?.label,
                            seed = collection.key,
                            tracks = collection.tracks,
                            art = art,
                            currentId = nowPlaying.mediaId,
                            onPlay = { start -> vm.play(collection.tracks, start); navController.popToHome() },
                            onShuffle = { vm.shuffle(collection.tracks); navController.popToHome() },
                            onLocked = { navController.navigate(Routes.locked(it.id)) },
                        )
                    }
                }

                composable(Routes.ARTISTS) {
                    ArtistsScreen(library?.artists.orEmpty(), art) { navController.navigate(Routes.artist(it.key)) }
                }
                composable(Routes.ARTIST) { entry ->
                    val key = entry.arguments?.getString("key")?.let(Uri::decode)
                    val artist = library?.artists?.firstOrNull { it.key == key }
                    if (artist != null) {
                        TrackListScreen(
                            title = artist.name,
                            subtitle = null,
                            seed = artist.key,
                            tracks = artist.tracks,
                            art = art,
                            currentId = nowPlaying.mediaId,
                            onPlay = { start -> vm.play(artist.tracks, start); navController.popToHome() },
                            onShuffle = { vm.shuffle(artist.tracks); navController.popToHome() },
                            onLocked = { navController.navigate(Routes.locked(it.id)) },
                        )
                    }
                }

                composable(Routes.SONGS) {
                    val songs = library?.songs.orEmpty()
                    TrackListScreen(
                        title = "Songs",
                        subtitle = null,
                        seed = "songs",
                        tracks = songs,
                        art = art,
                        currentId = nowPlaying.mediaId,
                        onPlay = { start -> vm.play(songs, start); navController.popToHome() },
                        onShuffle = { vm.shuffle(songs); navController.popToHome() },
                        onLocked = { navController.navigate(Routes.locked(it.id)) },
                    )
                }

                composable(Routes.LOCKED) { entry ->
                    val id = entry.arguments?.getString("id")?.let(Uri::decode)
                    val track = id?.let { library?.track(it) }
                    if (track != null) {
                        LockedScreen(
                            track = track,
                            refreshing = refreshing,
                            onShowAddress = { navController.navigate(Routes.ADDRESS) },
                            onCheckAgain = vm::refresh,
                            onPlay = { vm.play(listOf(track)); navController.popToHome() },
                        )
                    }
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        address = address,
                        refreshing = refreshing,
                        onAddress = { navController.navigate(Routes.ADDRESS) },
                        onRecovery = { navController.navigate(Routes.RECOVERY) },
                        onRefresh = vm::refresh,
                    )
                }
                composable(Routes.ADDRESS) {
                    AddressScreen(address = address, onDone = null)
                }
                composable(Routes.RECOVERY) {
                    RecoveryPhraseScreen(loadWords = vm::recoveryWords, onDone = { navController.popBackStack() })
                }
            }
        }
    }
}

/** After choosing music, land on Now Playing — the answer to "did that work?". */
private fun NavHostController.popToHome() {
    popBackStack(Routes.HOME, inclusive = false)
}
