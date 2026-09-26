package haven.mobile.core.cache

import kotlinx.coroutines.flow.Flow

/**
 * The user's cache preferences, as the cache sees them.
 *
 * An inversion, and for a concrete reason: the preferences live in `SettingsRepository` inside
 * `:core-cache-mirror`, and that module already depends on this one — so reaching for it directly
 * here would be a dependency cycle. `:core-cache` declares what it needs, `:core-cache-mirror`
 * supplies it.
 *
 * Without this the quota and TTL sliders wrote to DataStore and were never read: `foc`'s `Config` was
 * built from static defaults, so the two controls in Settings did nothing at all.
 */
interface CacheSettingsSource {
    val quotaBytes: Flow<Long>
    val ttlDays: Flow<Int>
}
