package haven.mobile.core.arkiv

/**
 * Arkiv connection settings.
 *
 * [endpointUrl] is the chain's JSON-RPC URL (Tiramisu by default), queried directly with
 * `arkiv_query` — there is no REST gateway in front of Arkiv. Production DI always supplies
 * a value (see `ArkivDiModule`); a blank URL only ever means "explicitly unconfigured",
 * never "offline" — offline is no connectivity at call time.
 */
data class ArkivConfig(
    val endpointUrl: String,
    val timeoutMillis: Long = 30_000,
    /** Server page size. 20 matches the web dApp so paging behaviour stays comparable. */
    val pageSize: Int = 20,
) {
    val isConfigured: Boolean get() = endpointUrl.isNotBlank()
}
