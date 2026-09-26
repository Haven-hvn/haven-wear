package haven.mobile.core.haven.aol

import org.json.JSONObject
import org.junit.Assert.*

/**
 * The v1 `GateRequest` typed data must be byte-equivalent to the dapp's
 * `buildGateRequestTypedData` — the canister verifies against pinned typehashes, so any drift
 * (a `version` domain field, an `itemId` message, an unquoted 256-bit nonce) signs a digest
 * the canister rejects. These pin the canonical shape field by field.
 */
class GateRequestBuilderTest {

    private val builder = GateRequestBuilder()

    private fun built() = JSONObject(
        builder.buildV1Request(
            evmAddress = "0xabc",
            transportPublicKeyHex = "0x" + "ab".repeat(48),
            nonceDecimal = "12345678901234567890",
        ),
    )

    private fun builtBatch() = JSONObject(
        builder.buildBatchV1Request(
            evmAddress = "0xabc",
            transportKeyHashHex = "0x" + "ab".repeat(32),
            cidsCommitmentHex = "0x" + "cd".repeat(32),
            nonceDecimal = "12345678901234567890",
        ),
    )

    @org.junit.Test
    fun `batch primary type and commitment field`() {
        val root = builtBatch()
        assertEquals("BatchGateRequest", root.getString("primaryType"))
        val message = root.getJSONObject("message")
        assertEquals("0xabc", message.getString("evmAddress"))
        assertEquals("0x" + "ab".repeat(32), message.getString("transportKeyHash"))
        assertEquals("0x" + "cd".repeat(32), message.getString("cidsCommitment"))
        assertEquals("12345678901234567890", message.getString("nonce"))
    }

    @org.junit.Test
    fun `batch type table matches the canister batch typehash`() {
        val types = builtBatch().getJSONObject("types")
        val fields = types.getJSONArray("BatchGateRequest")
        assertEquals(4, fields.length())
        assertEquals("evmAddress", fields.getJSONObject(0).getString("name"))
        assertEquals("address", fields.getJSONObject(0).getString("type"))
        assertEquals("transportKeyHash", fields.getJSONObject(1).getString("name"))
        assertEquals("bytes32", fields.getJSONObject(1).getString("type"))
        assertEquals("cidsCommitment", fields.getJSONObject(2).getString("name"))
        assertEquals("bytes32", fields.getJSONObject(2).getString("type"))
        assertEquals("nonce", fields.getJSONObject(3).getString("name"))
        assertEquals("uint256", fields.getJSONObject(3).getString("type"))
        // Same domain as the single request — the canister rebuilds one separator.
        val domain = builtBatch().getJSONObject("domain")
        assertEquals("HavenAOL", domain.getString("name"))
        assertTrue(!builtBatch().getJSONObject("types").getJSONArray("EIP712Domain").toString().contains("version"))
    }

    @org.junit.Test
    fun `batch canonical type string hashes to the canister pinned typehash`() {
        // `EIP712_BATCH_GATE_REQUEST_TYPEHASH_HEX` in the canister: a wallet and the
        // canister both derive this from the type table, so the field names, types
        // and order pinned above must hash to exactly this value — otherwise every
        // batch signature fails with InvalidSignature.
        val canonical =
            "BatchGateRequest(address evmAddress,bytes32 transportKeyHash,bytes32 cidsCommitment,uint256 nonce)"
        assertEquals(
            "b4633d97ed58755b24090d30395e8a391cb37f4e9c10d3478dc052697cf78394",
            haven.mobile.core.crypto.Keccak256.hashHex(canonical.toByteArray(Charsets.UTF_8)),
        )
    }

    @org.junit.Test
    fun `domain is HavenAOL without a version field`() {
        val domain = built().getJSONObject("domain")

        assertEquals("HavenAOL", domain.getString("name"))
        assertEquals(1, domain.getLong("chainId"))
        assertEquals("0x0000000000000000000000000000000000000000", domain.getString("verifyingContract"))
        assertTrue("version field breaks the pinned domain typehash", !domain.has("version"))
    }

    @org.junit.Test
    fun `gate chain overrides the domain chain id`() {
        val sepolia = JSONObject(
            builder.buildV1Request(
                evmAddress = "0xabc",
                transportPublicKeyHex = "0x" + "ab".repeat(48),
                nonceDecimal = "1",
                domainChainId = 11155111L,
            ),
        ).getJSONObject("domain")
        assertEquals(11155111L, sepolia.getLong("chainId"))
        assertEquals("HavenAOL", sepolia.getString("name"))
        assertTrue(!sepolia.has("version"))

        val batch = JSONObject(
            builder.buildBatchV1Request(
                evmAddress = "0xabc",
                transportKeyHashHex = "0x" + "ab".repeat(32),
                cidsCommitmentHex = "0x" + "cd".repeat(32),
                nonceDecimal = "1",
                domainChainId = 11155111L,
            ),
        ).getJSONObject("domain")
        assertEquals(11155111L, batch.getLong("chainId"))
    }

    @org.junit.Test
    fun `message binds wallet transport key and nonce`() {
        val message = built().getJSONObject("message")

        assertEquals("GateRequest", built().getString("primaryType"))
        assertEquals("0xabc", message.getString("evmAddress"))
        assertEquals("0x" + "ab".repeat(48), message.getString("transportPublicKey"))
        // Quoted decimal: a 256-bit nonce loses precision as a JSON number.
        assertEquals("12345678901234567890", message.getString("nonce"))
    }

    @org.junit.Test
    fun `type table matches the canister typehashes`() {
        val types = built().getJSONObject("types")
        val domainFields = types.getJSONArray("EIP712Domain")
        val requestFields = types.getJSONArray("GateRequest")

        assertEquals(3, domainFields.length())
        assertEquals("name", domainFields.getJSONObject(0).getString("name"))
        assertEquals("chainId", domainFields.getJSONObject(1).getString("name"))
        assertEquals("verifyingContract", domainFields.getJSONObject(2).getString("name"))
        assertEquals(3, requestFields.length())
        assertEquals("evmAddress", requestFields.getJSONObject(0).getString("name"))
        assertEquals("address", requestFields.getJSONObject(0).getString("type"))
        assertEquals("transportPublicKey", requestFields.getJSONObject(1).getString("name"))
        assertEquals("bytes", requestFields.getJSONObject(1).getString("type"))
        assertEquals("nonce", requestFields.getJSONObject(2).getString("name"))
        assertEquals("uint256", requestFields.getJSONObject(2).getString("type"))
    }

    @org.junit.Test
    fun `domain constants match the dapp deployment defaults`() {
        assertEquals(1L, GateRequestBuilder.EIP712_CHAIN_ID)
        assertEquals(
            "0x0000000000000000000000000000000000000000",
            GateRequestBuilder.EIP712_VERIFYING_CONTRACT,
        )
    }
}
