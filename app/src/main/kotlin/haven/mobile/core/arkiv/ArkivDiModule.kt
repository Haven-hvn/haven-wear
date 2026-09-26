package haven.mobile.core.arkiv

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ArkivDiModule {
    @Binds
    @Singleton
    abstract fun bindArkivClient(impl: ArkivClientImpl): ArkivClient
}

@Module
@InstallIn(SingletonComponent::class)
object ArkivConfigModule {
    /**
     * Endpoint comes from `local.properties` via `BuildConfig` (see this module's build script),
     * the same pattern as `wallet.projectId` and `haven.aol.*`. CI/release builds have no
     * `local.properties`, where `BuildConfig` would otherwise be blank — so a blank value falls
     * back to Tiramisu, the same default haven-dapp and haven-cli use. A missing config value
     * must never be what "offline" means; offline is no connectivity at call time.
     */
    @Provides
    @Singleton
    fun provideArkivConfig(): ArkivConfig = ArkivConfig(
        endpointUrl = haven.wear.BuildConfig.ARKIV_ENDPOINT_URL
            .ifBlank { "https://rpc.tiramisu.db-chain.testnet.arkiv.network" },
    )
}
