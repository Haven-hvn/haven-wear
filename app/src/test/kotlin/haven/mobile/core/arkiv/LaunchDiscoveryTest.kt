package haven.mobile.core.arkiv

import haven.mobile.core.domain.HavenChain
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Launch rows are a join: per-stage facts (`drip_id`, `drip_idx`, `mcap_usd`) from the PART, shared
 * facts (title, total, token, chain) from the SERIES header. These pin the extraction against the
 * writer shapes in dapp `v4/arkiv-publish` — including that a missing series degrades the row
 * rather than dropping it, while a missing target drops it like the dapp's `mcap > 0` filter.
 */
class LaunchDiscoveryTest {

    private val client = ArkivClientImpl(ArkivConfig(endpointUrl = "https://example.com"))

    private fun stageOf(partJson: String, seriesJson: String? = SERIES) = with(client) {
        val part = JSONObject(partJson)
        val series = seriesJson?.let { mapOf(DRIP_ID to JSONObject(it)) } ?: emptyMap()
        part.toLaunchStage(series)
    }

    @Test
    fun `part joins series facts`() {
        val stage = stageOf(PART)!!
        assertEquals("0xpart00000000000000000000000000000000000000000000000000000001", stage.id)
        assertEquals("POLYCAT — Genesis Drop", stage.title)
        assertEquals("0x1111111111111111111111111111111111111111", stage.gateToken)
        assertEquals(HavenChain.BASE_MAINNET, stage.gateChain)
        assertEquals(1_000_000, stage.marketCapTargetUsd)
        assertEquals(0, stage.dripIndex)
        assertEquals(3, stage.dripTotal)
        assertEquals(DRIP_ID, stage.dripId)
        assertEquals("polycat", stage.creatorHandle)
    }

    @Test
    fun `missing series degrades to untitled without token`() {
        val stage = stageOf(PART, seriesJson = null)!!
        assertEquals("Untitled Drop", stage.title)
        assertEquals("", stage.gateToken)
        assertNull(stage.gateChain)
        assertEquals(1, stage.dripTotal)
        assertNull(stage.creatorHandle)
        // The stage itself is still real: its id, index and target survive.
        assertEquals(1_000_000, stage.marketCapTargetUsd)
        assertEquals(0, stage.dripIndex)
    }

    @Test
    fun `part without target or drip id is dropped`() {
        assertNull(stageOf(PART_NO_MCAP))
        assertNull(stageOf(PART_ZERO_MCAP))
        assertNull(stageOf(PART_NO_DRIP_ID))
    }

    @Test
    fun `missing index reads as the premiere`() {
        val stage = stageOf(PART_NO_INDEX)!!
        assertEquals(0, stage.dripIndex)
    }

    @Test
    fun `unresolvable chain keeps the row with a null chain`() {
        val stage = stageOf(PART, seriesJson = SERIES_UNKNOWN_CHAIN)!!
        assertNull(stage.gateChain)
        assertEquals("0x1111111111111111111111111111111111111111", stage.gateToken)
    }

    @Test
    fun `string chain id resolves like the numeric form`() {
        val stage = stageOf(PART, seriesJson = SERIES_STRING_CHAIN)!!
        assertEquals(HavenChain.BASE_MAINNET, stage.gateChain)
    }

    private companion object {
        const val DRIP_ID = "drip-uuid-1"

        const val PART = """{
            "key": "0xpart00000000000000000000000000000000000000000000000000000001",
            "grp": "haven.video.drip.part",
            "gate_type": 4,
            "drip_id": "drip-uuid-1",
            "drip_idx": 0,
            "series_ref": "0xseries000000000000000000000000000000000000000000000000000001",
            "mcap_usd": 1000000,
            "createdAtBlock": 42
        }"""

        const val SERIES = """{
            "key": "0xseries000000000000000000000000000000000000000000000000000001",
            "grp": "haven.video.drip.series",
            "title": "POLYCAT — Genesis Drop",
            "gate_type": 4,
            "gate_token": "0x1111111111111111111111111111111111111111",
            "gate_chain": 8453,
            "gate_threshold": 1,
            "drip_id": "drip-uuid-1",
            "drip_total": 3,
            "payloadJson": "{\"targets\":[1000000,5000000,10000000],\"creator\":\"polycat\"}"
        }"""

        const val SERIES_UNKNOWN_CHAIN = """{
            "title": "Unknown chain drop",
            "gate_token": "0x1111111111111111111111111111111111111111",
            "gate_chain": 999999,
            "drip_total": 1
        }"""

        const val SERIES_STRING_CHAIN = """{
            "title": "String chain drop",
            "gate_token": "0x1111111111111111111111111111111111111111",
            "gate_chain": "8453",
            "drip_total": 1
        }"""

        const val PART_NO_MCAP = """{"key": "0x01", "drip_id": "drip-uuid-1", "drip_idx": 1}"""
        const val PART_ZERO_MCAP = """{"key": "0x01", "drip_id": "drip-uuid-1", "drip_idx": 1, "mcap_usd": 0}"""
        const val PART_NO_DRIP_ID = """{"key": "0x01", "drip_idx": 1, "mcap_usd": 1000}"""
        const val PART_NO_INDEX = """{"key": "0x01", "drip_id": "drip-uuid-1", "mcap_usd": 1000}"""
    }
}
