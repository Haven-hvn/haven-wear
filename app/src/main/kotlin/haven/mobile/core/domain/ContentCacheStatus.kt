package haven.mobile.core.domain

// Mirrors haven-dapp-main/src/types/cache.ts::ContentCacheStatus
enum class ContentCacheStatus {
    UNCACHED,
    PARTIAL,
    CACHED,
    EXPIRED
}