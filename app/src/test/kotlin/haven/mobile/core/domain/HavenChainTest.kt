package haven.mobile.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Gate chains arrive from three directions that do not agree, and the cost of getting one wrong is a
 * balance read against the wrong chain — which answers confidently and incorrectly. These pin the
 * accepted spellings and, more importantly, that anything unrecognised fails closed.
 */
class HavenChainTest {

    @Test
    fun `haven canonical names parse`() {
        assertEquals(HavenChain.ETH_MAINNET, HavenChain.parse("EthMainnet"))
        assertEquals(HavenChain.BASE_MAINNET, HavenChain.parse("BaseMainnet"))
        assertEquals(HavenChain.ARBITRUM_ONE, HavenChain.parse("ArbitrumOne"))
        assertEquals(HavenChain.OPTIMISM_MAINNET, HavenChain.parse("OptimismMainnet"))
        assertEquals(HavenChain.ETH_SEPOLIA, HavenChain.parse("EthSepolia"))
    }

    @Test
    fun `caip-2 and bare chain ids parse`() {
        assertEquals(HavenChain.ETH_MAINNET, HavenChain.parse("eip155:1"))
        assertEquals(HavenChain.BASE_MAINNET, HavenChain.parse("eip155:8453"))
        assertEquals(HavenChain.ARBITRUM_ONE, HavenChain.parse("42161"))
        assertEquals(HavenChain.OPTIMISM_MAINNET, HavenChain.parse("10"))
    }

    @Test
    fun `bare 10 is optimism, not ethereum`() {
        // The regression this guards: the previous implementation looked for the substrings "10" AND
        // "Optimism" together, so a plain "10" fell through to the EthMainnet default — a balance read
        // on the wrong chain.
        assertEquals(HavenChain.OPTIMISM_MAINNET, HavenChain.parse("10"))
    }

    @Test
    fun `human names and casing parse`() {
        assertEquals(HavenChain.ETH_MAINNET, HavenChain.parse("ethereum"))
        assertEquals(HavenChain.ETH_MAINNET, HavenChain.parse("ETH"))
        assertEquals(HavenChain.BASE_MAINNET, HavenChain.parse(" base "))
        assertEquals(HavenChain.ARBITRUM_ONE, HavenChain.parse("arb"))
        assertEquals(HavenChain.ETH_SEPOLIA, HavenChain.parse("sepolia"))
    }

    @Test
    fun `unknown chains fail closed rather than defaulting`() {
        assertNull(HavenChain.parse("polygon"))
        assertNull(HavenChain.parse("eip155:137"))
        assertNull(HavenChain.parse("solana"))
        assertNull(HavenChain.parse(""))
        assertNull(HavenChain.parse("   "))
        assertNull(HavenChain.parse(null))
    }

    @Test
    fun `caip-2 round-trips`() {
        HavenChain.entries.forEach { chain ->
            assertEquals(chain, HavenChain.parse(chain.caip2))
        }
    }

    @Test
    fun `mainnets exclude test networks`() {
        assertEquals(4, HavenChain.mainnets.size)
        assertEquals(false, HavenChain.mainnets.any { it.isTestnet })
    }

    @Test
    fun `a gate resolves its own chain`() {
        val gate = TokenGate(chain = "eip155:8453", tokenAddress = "0xabc", threshold = 1.0)
        assertEquals(HavenChain.BASE_MAINNET, gate.havenChain())

        val unsupported = TokenGate(chain = "eip155:137", tokenAddress = "0xabc", threshold = 1.0)
        assertNull(unsupported.havenChain())
    }
}
