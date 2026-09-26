package haven.mobile.core.cache

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CacheDiModule {
    @Binds
    @Singleton
    abstract fun bindHavenCache(impl: HavenCacheImpl): HavenCache
}

@Module
@InstallIn(SingletonComponent::class)
object CacheConfigModule {
    /**
     * Defaults per requirements FR-CACHE-2. The user-adjustable quota and TTL live in
     * `SettingsRepository`; wiring those through to a live foc `Config` is tracked follow-up work
     * (foc reads `Config` at construction, so the facade has to rebuild on change).
     */
    @Provides
    @Singleton
    fun provideCacheConfig(): CacheConfig = CacheConfig()

    /**
     * haven-wear: nobody decides what's on the watch. foc sizes its own quota from free space
     * (`AutoQuota`, set in HavenCacheImpl) and evicts least recently played first; the value here is
     * only the fallback if free space can't be read. Pieces are content-addressed and never go
     * stale, so there's no TTL (0 = never expire).
     */
    @Provides
    @Singleton
    fun provideCacheSettings(): CacheSettingsSource = object : CacheSettingsSource {
        override val quotaBytes = kotlinx.coroutines.flow.flowOf(FALLBACK_QUOTA)
        override val ttlDays = kotlinx.coroutines.flow.flowOf(0)
    }
}

private const val FALLBACK_QUOTA = 1L * 1024 * 1024 * 1024
