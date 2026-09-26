package haven.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dagger.hilt.android.AndroidEntryPoint
import haven.wear.library.LibraryRepository
import haven.wear.playback.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var player: PlayerConnection
    @Inject lateinit var library: LibraryRepository

    /** Bumped when the Tile asks to resume, so the UI can jump to Now Playing. */
    private val resumeRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        player.connect()
        handle(intent)
        setContent {
            WearApp(
                navController = rememberSwipeDismissableNavController(),
                resumeRequests = resumeRequests,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        library.refreshIfStale()
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_RESUME, false) == true) {
            intent.removeExtra(EXTRA_RESUME)
            player.resume()
            resumeRequests.value += 1
        }
    }

    companion object {
        const val EXTRA_RESUME = "haven.resume"
    }
}
