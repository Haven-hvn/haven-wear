package haven.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import haven.wear.di.AppScope
import haven.wear.library.LibraryRepository
import haven.wear.wallet.WatchWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class HavenWearApplication : Application() {

    @Inject lateinit var wallet: WatchWallet
    @Inject lateinit var library: LibraryRepository
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        // First launch creates the wallet silently; every launch refreshes the library in the
        // background. Neither blocks the first frame.
        scope.launch {
            runCatching { wallet.ensureCreated() }.onFailure { Timber.e(it, "wallet setup failed") }
            library.refreshIfStale()
        }
    }
}
