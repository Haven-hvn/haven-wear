package haven.mobile.core.collections

import haven.mobile.core.domain.HavenChain
import haven.wear.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where to read balances, per chain.
 *
 * Defaults are the same public endpoints `haven-dapp` uses (`lib/nft.ts`) — no account, no API key, no
 * per-user registration. That matters for two reasons: a fresh clone can answer "can I open this?"
 * without configuration, and the alternative (leaving it unset) makes the whole reading path depend on
 * a single config key.
 *
 * Each is overridable in `local.properties`, which is the escape hatch for anyone who would rather not
 * send wallet addresses to publicnode — worth knowing, because a balance query does reveal which
 * address is asking about which contract.
 */
@Singleton
class EvmEndpoints @Inject constructor() {

    fun rpcUrl(chain: HavenChain): String {
        val configured = when (chain) {
            HavenChain.ETH_MAINNET -> BuildConfig.RPC_ETHEREUM
            HavenChain.BASE_MAINNET -> BuildConfig.RPC_BASE
            HavenChain.ARBITRUM_ONE -> BuildConfig.RPC_ARBITRUM
            HavenChain.OPTIMISM_MAINNET -> BuildConfig.RPC_OPTIMISM
            HavenChain.ETH_SEPOLIA -> BuildConfig.RPC_SEPOLIA
        }
        return configured.ifBlank { defaultRpcUrl(chain) }
    }

    private fun defaultRpcUrl(chain: HavenChain): String = when (chain) {
        HavenChain.ETH_MAINNET -> "https://ethereum-rpc.publicnode.com"
        HavenChain.BASE_MAINNET -> "https://base-rpc.publicnode.com"
        HavenChain.ARBITRUM_ONE -> "https://arbitrum-one-rpc.publicnode.com"
        HavenChain.OPTIMISM_MAINNET -> "https://optimism-rpc.publicnode.com"
        HavenChain.ETH_SEPOLIA -> "https://ethereum-sepolia-rpc.publicnode.com"
    }
}
