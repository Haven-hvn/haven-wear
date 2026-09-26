package haven.mobile.core.domain

/**
 * A canister-signed holding proof, as embedded in an entity payload under `attn` by haven-cli.
 *
 * Mirrors `haven-dapp` `types/attestation`: two on-payload shapes coexist — [Single] from the
 * legacy `attest_holding` path and [Merkle] from v2 `batchAttestHolding` — discriminated by the
 * presence of `merkleRoot` + `merkleProof` (see dapp `isMerkleAttestation`). Readers verify
 * offline; see `core-attestation`.
 *
 * Numeric note: `threshold`/`balanceAtCheck` are Doubles exactly like the dapp's `number`
 * fields, and whole values format as plain integers in the signed preimages (matching the
 * canister's `Nat.toText`). Beyond 2^53 both platforms lose integer precision identically.
 */
sealed interface Attestation {
    val evmAddress: String
    /** Haven-AOL chain variant as recorded by the canister (`EthMainnet`, …). */
    val chain: String
    val tokenAddress: String
    val threshold: Double
    val balanceAtCheck: Double
    /** SHA-256 of the content CID — binds the proof to one entity (anti-replay). */
    val cidHash: String
    /** Unix seconds when the canister checked the balance. */
    val timestamp: Long

    data class Single(
        override val evmAddress: String,
        override val chain: String,
        override val tokenAddress: String,
        override val threshold: Double,
        override val balanceAtCheck: Double,
        override val cidHash: String,
        override val timestamp: Long,
        /** Hex Ed25519 signature over the leaf preimage (`0x` prefix tolerated). */
        val signature: String,
    ) : Attestation

    data class Merkle(
        override val evmAddress: String,
        override val chain: String,
        override val tokenAddress: String,
        override val threshold: Double,
        override val balanceAtCheck: Double,
        override val cidHash: String,
        override val timestamp: Long,
        /** Real (pre-pad) leaf count in the signed batch. */
        val cidCount: Long,
        /** Per-leaf proof, leaf → root, steps verbatim (`side` validated at verify time). */
        val merkleProof: List<MerkleProofStep>,
        /** 64-char lowercase hex, no `0x`. */
        val merkleRoot: String,
        /** Hex Ed25519 signature over the batch preimage (`0x` tolerated). */
        val rootSignature: String,
    ) : Attestation
}

/** One sibling hash in a per-leaf proof. `side` must read `left`/`right` to verify. */
data class MerkleProofStep(
    val side: String,
    /** 64-char lowercase hex, no `0x`. 32 raw bytes. */
    val hash: String,
)
