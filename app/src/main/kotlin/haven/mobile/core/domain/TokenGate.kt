package haven.mobile.core.domain

// Mirrors haven-dapp-main/src/types/gate.ts::TokenGate
data class TokenGate(
    val chain: String,
    val tokenAddress: String,
    val threshold: Double,
    val tokenStandard: TokenStandard = TokenStandard.ERC20
)