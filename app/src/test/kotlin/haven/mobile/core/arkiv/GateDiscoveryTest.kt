package haven.mobile.core.arkiv

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Gate discovery reads entity attributes, not a gate index: Arkiv has no gate concept, so
 * `gate_token` / `gate_chain` / `gate_threshold` stamped at publish time are the source of truth.
 * These pin the extraction the attribute scan depends on, in every spelling the gateway emits.
 */
class GateDiscoveryTest {

    private val client = ArkivClientImpl(ArkivConfig(endpointUrl = "https://example.com"))

    private fun gateOf(json: String) = with(client) { JSONObject(json).toTokenGate() }

    @Test
    fun `canonical 2_0 snake_case with numeric chain id`() {
        val gate = gateOf(
            """{"gate_token":"0xabcDEF1234567890abcdef1234567890ABCDEF12","gate_chain":8453,"gate_threshold":1}""",
        )!!
        assertEquals("eip155:8453", gate.chain)
        assertEquals("0xabcDEF1234567890abcdef1234567890ABCDEF12", gate.tokenAddress)
        assertEquals(1.0, gate.threshold)
    }

    @Test
    fun `camelCase spellings accepted`() {
        val gate = gateOf(
            """{"gateTokenAddress":"0xabcDEF1234567890abcdef1234567890ABCDEF12","gateChain":"BaseMainnet","gateThreshold":75}""",
        )!!
        assertEquals("eip155:8453", gate.chain)
        assertEquals(75.0, gate.threshold)
    }

    @Test
    fun `missing chain or contract yields nothing, never a guess`() {
        assertNull(gateOf("""{"gate_token":"0xabcDEF1234567890abcdef1234567890ABCDEF12"}"""))
        assertNull(gateOf("""{"gate_chain":8453}"""))
        assertNull(gateOf("""{"title":"untitled"}"""))
    }

    @Test
    fun `unknown chain yields nothing`() {
        assertNull(
            gateOf("""{"gate_token":"0xabcDEF1234567890abcdef1234567890ABCDEF12","gate_chain":999999}"""),
        )
    }
}
