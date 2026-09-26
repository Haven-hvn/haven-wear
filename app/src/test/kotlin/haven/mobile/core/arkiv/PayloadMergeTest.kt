package haven.mobile.core.arkiv

import haven.mobile.core.domain.GateMetadata
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The payload merges over the attributes — dapp parity (`parseArkivEntityToVideo` spreads
 * `{...attributes, ...payload}`): the piece CID, gate blobs, and locator fields live in the
 * payload, not the attributes, and without the merge every item reads as having no stored
 * content. Row identity stays reserved — the row's key/owner are facts about the row, never
 * payload claims.
 */
class PayloadMergeTest {

    private val client = ArkivClientImpl(ArkivConfig(endpointUrl = "https://example.com"))

    private fun hexPayload(json: String): String =
        "0x" + json.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun attr(name: String, type: String, value: Any) =
        JSONObject().put("name", name).put("type", type).put("value", value)

    /** The real `tiny` upload shape: gates in attributes, locators and sealed gates in payload. */
    private fun tinyRow(payloadJson: String, gateType: Int = 1): JSONObject = JSONObject()
        .put("key", "0x" + "ab".repeat(32))
        .put("owner", "0xOwner")
        .put("creator", "0xOwner")
        .put("createdAt", "0x9b877")
        .put("expiresAt", "0x1c2d77")
        .put("contentType", "application/json")
        .put("payload", hexPayload(payloadJson))
        .put(
            "attributes",
            JSONArray()
                .put(attr("title", "str", "tiny"))
                .put(attr("dur_s", "i32", 2))
                .put(attr("mime", "i32", 1))
                .put(attr("gate_chain", "i32", 11155111))
                .put(attr("gate_token", "str", "0xtoken"))
                .put(attr("gate_threshold", "i32", 1))
                .put(attr("gate_type", "i32", gateType))
                .put(attr("grp", "str", "haven.video.full"))
                .put(attr("sha256_ct", "str", "abc123")),
        )

    private fun sealedGate(version: Any = 1) = JSONObject()
        .put("version", version)
        .put("cid", "sha256:abc")
        .put("chain", "EthSepolia")
        .put("tokenAddress", "0xtoken")
        .put("threshold", "1")
        .put("encryptedAesKey", "SEALEDKEY")
        .toString()

    private fun tinyPayload() = JSONObject()
        .put("piece", "bafkpiece")
        .put("size", 10888)
        .put("gate", sealedGate())
        .put("cid_gate", sealedGate())
        .toString()

    private fun normalizedOf(payloadJson: String, gateType: Int = 1) = with(client) {
        normalizeRpcEntity(tinyRow(payloadJson, gateType))
    }

    @Test
    fun `payload piece and sealed gates surface on the item`() {
        val item = with(client) { normalizedOf(tinyPayload()).toMediaItem() }

        assertEquals("tiny", item.title)
        assertEquals("bafkpiece", item.pieceRef?.pieceCid)
        assertEquals(2L, item.durationSeconds)
        assertEquals("0xowner", item.owner)
        assertEquals(0x9b877L, item.createdAtBlock)
        assertTrue(item.isEncrypted, "sealed gate must read as encrypted")
        val gate = item.encryptionMetadata as? GateMetadata.Sealed
            ?: throw AssertionError("expected Sealed, got ${item.encryptionMetadata}")
        assertEquals(1L, gate.version)
        assertEquals("SEALEDKEY", gate.encryptedAesKey)
        assertEquals("sha256:abc", gate.cid)
        assertEquals("EthSepolia", gate.chain)
        assertEquals("0xtoken", gate.tokenAddress)
        assertEquals("1", gate.threshold)
        assertTrue(item.cidEncryptionMetadata is GateMetadata.Sealed, "cid layer sealed too")
        assertEquals("eip155:11155111", item.gate?.chain)
    }

    @Test
    fun `v3 sealed gate keeps its epoch, including epoch zero`() {
        // Byte shape of haven-aol `build_gate_metadata_v3` (field order pinned there).
        fun v3Gate(threshold: String, epoch: Int) = JSONObject()
            .put("version", 3)
            .put("cid", "bafkpiece")
            .put("chain", "EthSepolia")
            .put("tokenAddress", "0xtoken")
            .put("threshold", threshold)
            .put("epoch", epoch)
            .put("encryptedAesKey", "SEALEDKEY")
            .toString()
        fun gateOf(threshold: String, epoch: Int): GateMetadata.Sealed {
            val payload = JSONObject().put("piece", "bafkpiece").put("gate", v3Gate(threshold, epoch)).toString()
            val item = with(client) { normalizedOf(payload, gateType = 3).toMediaItem() }
            return item.encryptionMetadata as? GateMetadata.Sealed
                ?: throw AssertionError("expected Sealed, got ${item.encryptionMetadata}")
        }

        val gate = gateOf("1", 680)
        assertEquals(3L, gate.version)
        assertEquals(680L, gate.epoch)
        assertEquals("1", gate.threshold)
        assertEquals(0L, gateOf("0", 0).epoch, "epoch 0 is a value, not absence")
        assertEquals(3L, gate.attributeGateType)
        assertEquals(false, gate.gateTypeConflict)
        assertEquals(null, with(client) { normalizedOf(tinyPayload()).toMediaItem() }
            .let { (it.encryptionMetadata as GateMetadata.Sealed).epoch }, "v1 seals carry no epoch")
    }

    @Test
    fun `v4 drip gate keeps its reserve target and oracle`() {
        // Byte shape of haven-aol `build_gate_metadata_v4`.
        val v4Gate = JSONObject()
            .put("version", 4)
            .put("cid", "bafkpiece")
            .put("chain", "BaseMainnet")
            .put("tokenAddress", "0xtoken")
            .put("threshold", "5")
            .put("epoch", 670)
            .put("marketCapTarget", 12)
            .put("oracleAddress", "0xbond")
            .put("encryptedAesKey", "SEALEDKEY")
            .toString()
        val payload = JSONObject().put("piece", "bafkpiece").put("gate", v4Gate).toString()
        val gate = with(client) { normalizedOf(payload, gateType = 4).toMediaItem() }.encryptionMetadata
            as? GateMetadata.Sealed ?: throw AssertionError("v4 drips are sealed records")

        assertEquals(4L, gate.version)
        assertTrue(gate.isMarketCapDrip)
        assertEquals(12L, gate.marketCapTarget)
        assertEquals("0xbond", gate.oracleAddress)
        assertEquals(670L, gate.epoch)
        assertEquals(false, gate.gateTypeConflict)
    }

    @Test
    fun `gate_type attribute that disagrees with the gate version is flagged`() {
        // tinyPayload() carries v1 seals; the row claims gate_type 3.
        val gate = with(client) { normalizedOf(tinyPayload(), gateType = 3).toMediaItem() }.encryptionMetadata
            as GateMetadata.Sealed

        assertEquals(3L, gate.attributeGateType)
        assertTrue(gate.gateTypeConflict)
    }

    @Test
    fun `payload wins over attributes like the dapp spread`() {
        val payload = JSONObject().put("title", "payload-title").toString()
        val item = with(client) { normalizedOf(payload).toMediaItem() }

        assertEquals("payload-title", item.title)
    }

    @Test
    fun `row identity survives a hostile payload`() {
        val payload = JSONObject()
            .put("owner", "0xevil")
            .put("key", "0xevil")
            .put("id", "evil")
            .put("createdAtBlock", 999)
            .put("contentType", "evil")
            .toString()
        val normalized = normalizedOf(payload)

        assertEquals("0xOwner", normalized.optString("owner", null))
        assertEquals("0x" + "ab".repeat(32), normalized.optString("key", null))
        assertEquals(0x9b877L, normalized.optLong("createdAtBlock"))
        assertEquals("application/json", normalized.optString("contentType", null))
    }

    @Test
    fun `sealed record without a version still reads as sealed`() {
        val payload = JSONObject()
            .put("gate", sealedGate(version = "not-a-number"))
            .toString()
        val item = with(client) { normalizedOf(payload).toMediaItem() }

        val gate = item.encryptionMetadata as? GateMetadata.Sealed
            ?: throw AssertionError("expected Sealed, got ${item.encryptionMetadata}")
        assertEquals(0L, gate.version)
        assertTrue(item.isEncrypted)
    }

    @Test
    fun `legacy wrapped-key records still parse as before`() {
        val legacy = JSONObject().put("wrappedKey", "abc").put("nonce", "n").toString()
        val payload = JSONObject().put("gate", legacy).toString()
        val item = with(client) { normalizedOf(payload).toMediaItem() }

        val gate = item.encryptionMetadata as? GateMetadata.V1
            ?: throw AssertionError("expected V1, got ${item.encryptionMetadata}")
        assertEquals("n", gate.nonce)
        assertTrue(item.isEncrypted)
    }

    @Test
    fun `unparseable payload degrades to attributes only`() {
        val item = with(client) { normalizedOf("not json {{{").toMediaItem() }

        assertEquals("tiny", item.title)
        assertEquals(null, item.pieceRef)
    }
}
