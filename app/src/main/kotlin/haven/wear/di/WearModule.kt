package haven.wear.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import haven.mobile.core.collections.EvmGateAccessChecker
import haven.mobile.core.collections.GateAccessChecker
import haven.mobile.core.wallet.WalletSession
import haven.wear.wallet.WatchWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-lifetime scope for work that must outlive a screen (refreshes, prefetch). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
abstract class WearBindings {
    /** The ported unlock core and cache see the watch's own wallet as their WalletSession. */
    @Binds
    @Singleton
    abstract fun bindWalletSession(impl: WatchWallet): WalletSession

    @Binds
    @Singleton
    internal abstract fun bindGateAccessChecker(impl: EvmGateAccessChecker): GateAccessChecker
}

@Module
@InstallIn(SingletonComponent::class)
object WearModule {
    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
