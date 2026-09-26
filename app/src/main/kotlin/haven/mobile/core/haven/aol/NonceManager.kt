package haven.mobile.core.haven.aol

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

class NonceManager {
    private val nonceCache = mutableMapOf<String, String>()
    private val mutex = Mutex()

    suspend fun getNonce(walletAddress: String, canisterId: String): String {
        val key = nonceKey(walletAddress, canisterId)
        return mutex.withLock {
            nonceCache[key] ?: generateNonce()
        }
    }

    suspend fun refreshNonce(walletAddress: String, canisterId: String) {
        val key = nonceKey(walletAddress, canisterId)
        mutex.withLock {
            nonceCache[key] = generateNonce()
        }
    }

    suspend fun clearFor(walletAddress: String, canisterId: String) {
        val key = nonceKey(walletAddress, canisterId)
        mutex.withLock {
            nonceCache.remove(key)
        }
    }

    private fun nonceKey(walletAddress: String, canisterId: String): String {
        return walletAddress + ":" + canisterId
    }

    /**
     * Fresh EIP-712 `uint256` in `[1, 2^256)`, decimal-encoded — dapp parity with
     * `createRandomGateNonce`. Decimal, never hex: the value embeds in the typed-data JSON
     * (where a 256-bit number loses precision and hex is not valid) and parses to the Candid
     * `Nat` verbatim. Fresh on every call — the canister rejects replayed nonces.
     */
    private fun generateNonce(): String {
        while (true) {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val value = java.math.BigInteger(1, bytes)
            if (value != java.math.BigInteger.ZERO) return value.toString(10)
        }
    }
}