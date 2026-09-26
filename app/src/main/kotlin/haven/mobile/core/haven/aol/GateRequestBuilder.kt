package haven.mobile.core.haven.aol

import haven.mobile.core.domain.GateMetadata
import haven.mobile.core.domain.MediaItem
import haven.mobile.core.domain.TokenGate
import haven.mobile.core.wallet.WalletSession

class GateRequestBuilder {
    /**
     * Canonical v1 `GateRequest` typed data — byte-equivalent to the dapp's
     * `buildGateRequestTypedData`, which the canister verifies against its pinned typehashes
     * (`EIP712Domain(string name,uint256 chainId,address verifyingContract)` — no `version`
     * field — and `GateRequest(address evmAddress,bytes transportPublicKey,uint256 nonce)`).
     * Any drift here signs a digest the canister rejects, so the shape pins in tests.
     *
     * The nonce travels as a quoted decimal string: a 256-bit value loses precision as a JSON
     * number, and wallets encode `uint256` from decimal strings exactly.
     */
    /**
     * Canonical v1 *batch* `BatchGateRequest` typed data for
     * `batchRequestDecryptionKey`:
     * `BatchGateRequest(address evmAddress,bytes32 transportKeyHash,bytes32 cidsCommitment,uint256 nonce)`.
     *
     * Unlike the single request (whose dynamic `bytes` transport key the wallet
     * hashes itself), the batch struct takes `transportKeyHash` pre-hashed
     * (bytes32): `keccak256(transportPublicKey)`. So does `cidsCommitment`:
     * `keccak256(derivationInput₁ ‖ derivationInput₂ ‖ …)` in submitted order —
     * exactly the canister's `eip712BatchGateStructHash`. Same domain as the
     * single request; drift signs a digest the canister rejects with
     * InvalidSignature, so the shape pins in tests.
     */
    fun buildBatchV1Request(
        evmAddress: String,
        transportKeyHashHex: String,
        cidsCommitmentHex: String,
        nonceDecimal: String,
        domainChainId: Long = EIP712_CHAIN_ID,
    ): String {
        return """
            {
                "types": {
                    "EIP712Domain": [
                        {"name": "name", "type": "string"},
                        {"name": "chainId", "type": "uint256"},
                        {"name": "verifyingContract", "type": "address"}
                    ],
                    "BatchGateRequest": [
                        {"name": "evmAddress", "type": "address"},
                        {"name": "transportKeyHash", "type": "bytes32"},
                        {"name": "cidsCommitment", "type": "bytes32"},
                        {"name": "nonce", "type": "uint256"}
                    ]
                },
                "primaryType": "BatchGateRequest",
                "domain": {
                    "name": "HavenAOL",
                    "chainId": $domainChainId,
                    "verifyingContract": "$EIP712_VERIFYING_CONTRACT"
                },
                "message": {
                    "evmAddress": "$evmAddress",
                    "transportKeyHash": "$transportKeyHashHex",
                    "cidsCommitment": "$cidsCommitmentHex",
                    "nonce": "$nonceDecimal"
                }
            }
        """.trimIndent()
    }

    fun buildV1Request(
        evmAddress: String,
        transportPublicKeyHex: String,
        nonceDecimal: String,
        domainChainId: Long = EIP712_CHAIN_ID,
    ): String {
        return """
            {
                "types": {
                    "EIP712Domain": [
                        {"name": "name", "type": "string"},
                        {"name": "chainId", "type": "uint256"},
                        {"name": "verifyingContract", "type": "address"}
                    ],
                    "GateRequest": [
                        {"name": "evmAddress", "type": "address"},
                        {"name": "transportPublicKey", "type": "bytes"},
                        {"name": "nonce", "type": "uint256"}
                    ]
                },
                "primaryType": "GateRequest",
                "domain": {
                    "name": "HavenAOL",
                    "chainId": $domainChainId,
                    "verifyingContract": "$EIP712_VERIFYING_CONTRACT"
                },
                "message": {
                    "evmAddress": "$evmAddress",
                    "transportPublicKey": "$transportPublicKeyHex",
                    "nonce": "$nonceDecimal"
                }
            }
        """.trimIndent()
    }

    companion object {
        /**
         * EIP-712 domain fallback, dapp defaults (`NEXT_PUBLIC_EIP712_CHAIN_ID=1`, zero
         * verifier). The canister rebuilds the domain separator from the request's values,
         * so the signed data and the Candid call just have to match each other — and for
         * sealed-v1 the app now sends the gate's own chain (Sepolia gates sign a Sepolia
         * domain) instead of always 1, so the signature commits to the chain actually
         * checked and the wallet request surfaces on the session the reader uses.
         * Shared constants so the two can never drift apart.
         */
        const val EIP712_CHAIN_ID = 1L
        const val EIP712_VERIFYING_CONTRACT = "0x0000000000000000000000000000000000000000"
    }

    /**
     * Canonical v3 `GateRequestV3` typed data for `requestDecryptionKeyV3` — byte-equivalent to
     * haven-aol `buildGateRequestV3TypedData` and verified by the canister against
     * `GateRequestV3(address evmAddress,bytes transportPublicKey,uint256 epoch,uint256 nonce)`
     * (typehash `bf3ae938…d7af`, `src/backend/main.mo`). Same three-field `HavenAOL` domain as
     * v1 — no `version`. There is no item or CID in it: one signature opens a whole
     * (gate, epoch) bucket. `epoch` and `nonce` are quoted decimals for the same precision
     * reason as v1.
     */
    fun buildV3Request(
        evmAddress: String,
        transportPublicKeyHex: String,
        epochDecimal: String,
        nonceDecimal: String,
        domainChainId: Long = EIP712_CHAIN_ID,
    ): String {
        return """
            {
                "types": {
                    "EIP712Domain": [
                        {"name": "name", "type": "string"},
                        {"name": "chainId", "type": "uint256"},
                        {"name": "verifyingContract", "type": "address"}
                    ],
                    "GateRequestV3": [
                        {"name": "evmAddress", "type": "address"},
                        {"name": "transportPublicKey", "type": "bytes"},
                        {"name": "epoch", "type": "uint256"},
                        {"name": "nonce", "type": "uint256"}
                    ]
                },
                "primaryType": "GateRequestV3",
                "domain": {
                    "name": "HavenAOL",
                    "chainId": $domainChainId,
                    "verifyingContract": "$EIP712_VERIFYING_CONTRACT"
                },
                "message": {
                    "evmAddress": "$evmAddress",
                    "transportPublicKey": "$transportPublicKeyHex",
                    "epoch": "$epochDecimal",
                    "nonce": "$nonceDecimal"
                }
            }
        """.trimIndent()
    }

    /**
     * Canonical v4 `GateRequestV4` typed data for `requestDecryptionKeyV4` — verified by the
     * canister against `GateRequestV4(address evmAddress,bytes transportPublicKey,uint256 epoch,
     * uint256 marketCapTarget,uint256 nonce)` (typehash `b9d5f143…0afd`, `src/backend/main.mo`).
     * The signature commits to the drip stage's target, so one signature cannot be replayed
     * for a later, higher stage. Same `HavenAOL` domain as v1/v3.
     */
    fun buildV4Request(
        evmAddress: String,
        transportPublicKeyHex: String,
        epochDecimal: String,
        marketCapTargetDecimal: String,
        nonceDecimal: String,
        domainChainId: Long = EIP712_CHAIN_ID,
    ): String {
        return """
            {
                "types": {
                    "EIP712Domain": [
                        {"name": "name", "type": "string"},
                        {"name": "chainId", "type": "uint256"},
                        {"name": "verifyingContract", "type": "address"}
                    ],
                    "GateRequestV4": [
                        {"name": "evmAddress", "type": "address"},
                        {"name": "transportPublicKey", "type": "bytes"},
                        {"name": "epoch", "type": "uint256"},
                        {"name": "marketCapTarget", "type": "uint256"},
                        {"name": "nonce", "type": "uint256"}
                    ]
                },
                "primaryType": "GateRequestV4",
                "domain": {
                    "name": "HavenAOL",
                    "chainId": $domainChainId,
                    "verifyingContract": "$EIP712_VERIFYING_CONTRACT"
                },
                "message": {
                    "evmAddress": "$evmAddress",
                    "transportPublicKey": "$transportPublicKeyHex",
                    "epoch": "$epochDecimal",
                    "marketCapTarget": "$marketCapTargetDecimal",
                    "nonce": "$nonceDecimal"
                }
            }
        """.trimIndent()
    }
}
