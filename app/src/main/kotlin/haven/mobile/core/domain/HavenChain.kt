package haven.mobile.core.domain

/**
 * The chains Haven-AOL can evaluate a gate on.
 *
 * This is not a list of chains that exist — it is the canister's `GateChain` variant, so it is exactly
 * as long as what the canister will accept. Adding an entry here without the canister supporting it
 * produces gates that always fail to unlock.
 *
 * Source of truth: `haven-dapp/src/lib/haven-aol/haven-aol-client.ts` (the Candid variant) and
 * `VALID_CHAINS` in its decrypt tests.
 *
 * [aolVariant] is the wire name — the Candid variant label, sent verbatim in a `GateRequest`.
 * [chainId] is the EIP-155 id, used to read balances and to match `eip155:` gate strings.
 */
enum class HavenChain(
    val aolVariant: String,
    val chainId: Long,
    val label: String,
    val isTestnet: Boolean = false,
    /** mint.club / TrustWallet network key for trade and token URLs (mirrors dapp `gate-chains`). */
    val mintClubKey: String,
) {
    ETH_MAINNET("EthMainnet", 1, "Ethereum", false, "ethereum"),
    BASE_MAINNET("BaseMainnet", 8453, "Base", false, "base"),
    ARBITRUM_ONE("ArbitrumOne", 42161, "Arbitrum", false, "arbitrum"),
    OPTIMISM_MAINNET("OptimismMainnet", 10, "Optimism", false, "optimism"),
    ETH_SEPOLIA("EthSepolia", 11155111, "Sepolia", true, "sepolia"),
    ;

    /** CAIP-2, the form used in `TokenGate.chain` when it is written canonically. */
    val caip2: String get() = "eip155:$chainId"

    companion object {
        /** Everything a reader would normally want checked: the mainnets. */
        val mainnets: List<HavenChain> get() = entries.filter { !it.isTestnet }

        /**
         * Parse whatever a gate happens to carry.
         *
         * Gate strings arrive from three directions and none of them agree: Haven's own canonical
         * names (`EthMainnet`), CAIP-2 (`eip155:8453`), bare chain ids (`8453`), and human names
         * (`base`). A gate that cannot be parsed must not silently become Ethereum — that would check
         * a balance on the wrong chain and answer confidently with the wrong answer — so this returns
         * null and callers skip the gate.
         */
        fun parse(raw: String?): HavenChain? {
            val value = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null

            // eip155:8453 / 8453
            val numeric = value.removePrefix("eip155:").toLongOrNull()
            if (numeric != null) return entries.firstOrNull { it.chainId == numeric }

            return when (value) {
                "ethmainnet", "ethereum", "eth", "mainnet", "eth-mainnet" -> ETH_MAINNET
                "basemainnet", "base", "base-mainnet" -> BASE_MAINNET
                "arbitrumone", "arbitrum", "arb", "arbitrum-one" -> ARBITRUM_ONE
                "optimismmainnet", "optimism", "op", "optimism-mainnet" -> OPTIMISM_MAINNET
                "ethsepolia", "sepolia", "eth-sepolia" -> ETH_SEPOLIA
                else -> null
            }
        }
    }
}

/** The chain this gate is evaluated on, or null if it names something Haven-AOL cannot check. */
fun TokenGate.havenChain(): HavenChain? = HavenChain.parse(chain)
