package haven.mobile.core.haven.aol

data class HavenAolConfig(
    val canisterId: String,
    val icHost: String,
    val requestTimeoutMillis: Long = 30_000
)