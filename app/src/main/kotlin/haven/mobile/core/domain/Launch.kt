package haven.mobile.core.domain

/**
 * One globally-visible launch stage.
 *
 * Parity with `haven-dapp`'s `DropItem` (`components/drops/UpcomingDrops`): a drip PART's own facts
 * (`drip_id`, `drip_idx`, `mcap_usd`) joined to its SERIES header's shared facts (title, total,
 * token, chain). Parts carry no gate attributes, so without the join a stage is an unlock target
 * with no token to pump and no title to show.
 *
 * Unlike [MediaItem], this is not gated on what the wallet can open — launches are global
 * discovery. Tapping a stage deep-links to watch by part key, where the pump sheet or the
 * premiere renders as usual.
 */
data class LaunchStage(
    /** Part entity key — the watch deep-link target. */
    val id: String,
    /** Series `title`, or "Untitled Drop" when the series header is missing. */
    val title: String,
    /** Series `gate_token`; blank when the series header is missing. */
    val gateToken: String,
    /** Series `gate_chain` resolved; null when it names something Haven-AOL cannot check. */
    val gateChain: HavenChain?,
    /** Whole-USD unlock target for this stage (`mcap_usd`). */
    val marketCapTargetUsd: Long,
    /** 0-based stage index (`drip_idx`). */
    val dripIndex: Int,
    /** Stage count from the series header (`drip_total`, 1 when unknown). */
    val dripTotal: Int,
    /** Stable per-launch id grouping stages (`drip_id`). */
    val dripId: String,
    /** Series payload `creator`, the only human identity a launch carries. */
    val creatorHandle: String?,
    /** Part `createdAt` block height, for newest-launch-first ordering. */
    val createdAtBlock: Long?,
)

/**
 * Reader-side launch death: keep only the contiguous stage prefix per launch.
 *
 * Port of dapp `selectLiveStages` (`lib/parse-arkiv-video.ts`). Arkiv entities expire
 * independently, so a launch can die partially — stage 2 expires while 3–5 live on. Writers accept
 * that (unreleased content should die); readers therefore treat a launch with a hole as dead:
 * stages stop at the first gap, and a launch missing stage 0 disappears entirely. Stages are
 * 0-based (`drip_idx`); input order is preserved.
 *
 * Best-effort under pagination: callers that cap their part query can mistake a cut-off page for a
 * gap. Fetch whole launches before applying this.
 */
fun selectLiveStages(items: List<LaunchStage>): List<LaunchStage> {
    val live = mutableSetOf<LaunchStage>()
    for (group in items.groupBy { it.dripId }.values) {
        val byIndex = mutableMapOf<Int, LaunchStage>()
        for (item in group) {
            if (item.dripIndex >= 0 && item.dripIndex !in byIndex) {
                byIndex[item.dripIndex] = item
            }
        }
        var index = 0
        while (byIndex.containsKey(index)) {
            live.add(byIndex.getValue(index))
            index++
        }
    }
    return items.filter { it in live }
}
