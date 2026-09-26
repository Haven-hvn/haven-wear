package haven.mobile.core.cache

data class CacheSpace(
    val quotaBytes: Long,
    val usedBytes: Long,
    val itemCount: Int,
)