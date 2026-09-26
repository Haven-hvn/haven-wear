package haven.mobile.core.arkiv

data class ArkivPage<T>(
    val items: List<T>,
    val nextCursor: String?,
)