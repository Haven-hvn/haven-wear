package haven.mobile.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A launch with a hole is dead past the hole: stages stop at the first gap, and a launch missing
 * stage 0 disappears entirely. These pin the reader-side rule against the dapp's
 * `selectLiveStages` cases (expired middle stage, missing premiere, duplicate re-publish).
 */
class LaunchStagesTest {

    private fun stage(dripId: String, index: Int) = LaunchStage(
        id = "$dripId:$index",
        title = "Drop",
        gateToken = "0x1111111111111111111111111111111111111111",
        gateChain = HavenChain.BASE_MAINNET,
        marketCapTargetUsd = 1_000_000,
        dripIndex = index,
        dripTotal = 3,
        dripId = dripId,
        creatorHandle = null,
        createdAtBlock = null,
    )

    @Test
    fun `contiguous launch survives whole`() {
        val items = listOf(stage("a", 0), stage("a", 1), stage("a", 2))
        assertEquals(items, selectLiveStages(items))
    }

    @Test
    fun `stages stop at the first gap`() {
        val items = listOf(stage("a", 0), stage("a", 1), stage("a", 3), stage("a", 4))
        assertEquals(listOf(stage("a", 0), stage("a", 1)), selectLiveStages(items))
    }

    @Test
    fun `launch missing stage zero disappears entirely`() {
        val items = listOf(stage("a", 1), stage("a", 2))
        assertEquals(emptyList<LaunchStage>(), selectLiveStages(items))
    }

    @Test
    fun `launches are independent and input order is preserved`() {
        // "a" is gapped (stage 1 gone) so only its stage 0 lives; "b" is whole. Output order
        // follows the input, not the index — one launch's hole never truncates another.
        val items = listOf(stage("b", 1), stage("a", 0), stage("b", 0), stage("a", 2))
        assertEquals(
            listOf(stage("b", 1), stage("a", 0), stage("b", 0)),
            selectLiveStages(items),
        )
    }

    @Test
    fun `duplicate index keeps the first occurrence only`() {
        val first = stage("a", 0).copy(id = "first")
        val second = stage("a", 0).copy(id = "second")
        val items = listOf(first, second, stage("a", 1))
        assertEquals(listOf(first, stage("a", 1)), selectLiveStages(items))
    }
}
