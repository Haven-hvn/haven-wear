package haven.mobile.core.arkiv

import haven.mobile.core.domain.Attestation
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Wire-format parity for `arkiv_query` rows.
 *
 * The node returns SDK 0.8 `RpcEntity` rows — flat `key`/`owner`/`creator`/`createdAt`/
 * `expiresAt`/`contentType` fields plus `attributes` as `[{name, type, value}]` with
 * type-tagged values (see SDK `entityFromRpcResult` and CLI `decode_rpc_entity`, which this
 * decoder mirrors). No Haven entities exist on-chain to capture from, so the fixture below is
 * transcribed from those two decoders rather than from a live response.
 *
 * This pins the decode the whole read path depends on: gate extraction, block heights, the
 * MIME enum, and the skip-corrupt-rows rule.
 */
class ArkivWireFormatTest {

    private val client = ArkivClientImpl(ArkivConfig(endpointUrl = "https://example.com"))

    private fun normalized(json: String): JSONObject = client.normalizeRpcEntity(JSONObject(json))

    @Test
    fun `row identity and block heights land under reader keys`() {
        val out = normalized(RPC_ROW)
        assertEquals("0x1234", out.optString("id"))
        assertEquals("0x1234", out.optString("key"))
        assertEquals("0xbeef", out.optString("owner"))
        assertEquals("video/mp4", out.optString("contentType"))
        assertEquals(256L, out.optLong("createdAtBlock"))
        assertEquals(512L, out.optLong("expiresAtBlock"))
    }

    @Test
    fun `gate triple decodes through to a token gate`() {
        val gate = with(client) { normalized(RPC_ROW).toTokenGate() }!!
        assertEquals("eip155:8453", gate.chain)
        assertEquals("0xabcDEF1234567890abcdef1234567890ABCDEF12", gate.tokenAddress)
        assertEquals(75.0, gate.threshold)
    }

    @Test
    fun `attribute tags decode to flat values`() {
        val out = normalized(RPC_ROW)
        assertEquals("haven.video.full", out.optString("grp"))
        assertEquals(1L, out.optLong("mime"))
        assertEquals(60L, out.optLong("dur_s"))
        assertEquals(true, out.optBoolean("flag"))
        assertEquals("12.5", out.optString("ratio"))
    }

    @Test
    fun `oversized u256 degrades to double, never null`() {
        val out = normalized(RPC_ROW)
        assertTrue(out.opt("whale") is Double)
    }

    @Test
    fun `unknown tags and malformed values are skipped`() {
        val out = normalized(RPC_ROW)
        assertFalse(out.has("weird"))
        assertFalse(out.has("broken"))
    }

    @Test
    fun `payload attn single parses`() {
        val out = normalized(RPC_ROW_SINGLE_ATTN)
        val att = with(client) { out.parseAttestationOrNull() } as? Attestation.Single
            ?: fail("expected Single attestation")
        assertEquals("BaseMainnet", att.chain)
        assertEquals(75.0, att.threshold)
        assertEquals(1781760000L, att.timestamp)
        assertEquals("873f55d8", att.signature)
    }

    @Test
    fun `payload attn merkle parses with proof steps verbatim`() {
        val out = normalized(RPC_ROW_MERKLE_ATTN)
        val att = with(client) { out.parseAttestationOrNull() } as? Attestation.Merkle
            ?: fail("expected Merkle attestation")
        assertEquals(2L, att.cidCount)
        assertEquals(1, att.merkleProof.size)
        assertEquals("right", att.merkleProof[0].side)
        assertEquals("3027e1b9b7805f562f9bfaa4abaf9d5a9987b36828d0eca7049bd2f3595b3d8a", att.merkleRoot)
    }

    @Test
    fun `partial attn fails closed`() {
        // Proof without root lands in the Single branch, which rejects the missing signature.
        assertNull(attnOf("""{"attn":{"evmAddress":"0x1","chain":"c","tokenAddress":"0x2","threshold":1,"balanceAtCheck":1,"cidHash":"h","timestamp":1,"merkleProof":[]}}"""))
        assertNull(attnOf("""{"attn":{"evmAddress":"0x1"}}"""))
        assertNull(attnOf("""{"noAttn":true}"""))
        assertNull(attnOf("not json"))
        assertNull(with(client) { normalized(RPC_ROW).parseAttestationOrNull() })
    }

    @Test
    fun `creator address survives creator attribute`() {
        val out = normalized(RPC_ROW_CREATOR_ATTR)
        assertEquals("0xadd0000000000000000000000000000000000ress", out.optString("creatorAddress"))
        assertEquals("moth.eth", out.optString("creator"))
    }

    private fun attnOf(payloadJson: String): Attestation? =
        with(client) { JSONObject().put("payloadJson", payloadJson).parseAttestationOrNull() }

    private companion object {
        /** Rows carrying hex-encoded JSON payloads with `attn` receipts (see class KDoc). */
        const val RPC_ROW_SINGLE_ATTN = """{"key":"0x1","payload":"0x7b226174746e223a7b2265766d41646472657373223a22307835433332343639333235643430393361423134324444644331333035463931643736653435313431222c22636861696e223a22426173654d61696e6e6574222c22746f6b656e41646472657373223a22307861626344454631323334353637383930616263646566313233343536373839304142434445463132222c227468726573686f6c64223a37352c2262616c616e63654174436865636b223a3132302c2263696448617368223a2261616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161222c2274696d657374616d70223a313738313736303030302c227369676e6174757265223a223837336635356438227d7d"}"""
        const val RPC_ROW_MERKLE_ATTN = """{"key":"0x2","payload":"0x7b226174746e223a7b2265766d41646472657373223a22307835433332343639333235643430393361423134324444644331333035463931643736653435313431222c22636861696e223a22426173654d61696e6e6574222c22746f6b656e41646472657373223a22307861626344454631323334353637383930616263646566313233343536373839304142434445463132222c227468726573686f6c64223a37352c2262616c616e63654174436865636b223a3132302c2263696448617368223a2231313131313131313131313131313131313131313131313131313131313131313131313131313131313131313131313131313131313131313131313131313131222c2274696d657374616d70223a313738313736303030302c22636964436f756e74223a322c226d65726b6c6550726f6f66223a5b7b2273696465223a227269676874222c2268617368223a2261666335643631303834653739336566323763666530393964303230666438656435316531366161646534373964366666376138393164373631343963633131227d5d2c226d65726b6c65526f6f74223a2233303237653162396237383035663536326639626661613461626166396435613939383762333638323864306563613730343962643266333539356233643861222c22726f6f745369676e6174757265223a226339623738316164227d7d"}"""
        const val RPC_ROW_CREATOR_ATTR = """{"key":"0x3","creator":"0xadd0000000000000000000000000000000000ress","attributes":[{"name":"creator","type":"str","value":"moth.eth"}]}"""

        /** One `arkiv_query` row in the SDK 0.8 `RpcEntity` shape (see class KDoc). */
        const val RPC_ROW = """
            {
              "key": "0x1234",
              "owner": "0xbeef",
              "creator": "0xbeef",
              "createdAt": "0x100",
              "expiresAt": "0x200",
              "contentType": "video/mp4",
              "attributes": [
                {"name": "grp", "type": "str", "value": "haven.video.full"},
                {"name": "title", "type": "str", "value": "Test video"},
                {"name": "mime", "type": "i32", "value": 1},
                {"name": "gate_token", "type": "str", "value": "0xabcDEF1234567890abcdef1234567890ABCDEF12"},
                {"name": "gate_chain", "type": "i32", "value": "0x2105"},
                {"name": "gate_threshold", "type": "i32", "value": 75},
                {"name": "dur_s", "type": "u64", "value": "0x3c"},
                {"name": "whale", "type": "u256", "value": "0xffffffffffffffffffffffffffffffff"},
                {"name": "flag", "type": "bool", "value": true},
                {"name": "ratio", "type": "dec", "value": "12.5"},
                {"name": "blob", "type": "bytes", "value": "0xdeadbeef"},
                {"name": "weird", "type": "nope", "value": "x"},
                {"name": "broken", "type": "i32", "value": "not-a-number"}
              ]
            }
        """
    }
}
