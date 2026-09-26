package haven.wear.wallet

import org.json.JSONObject
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.MnemonicUtils
import org.web3j.crypto.Sign
import org.web3j.crypto.StructuredDataEncoder

/**
 * The watch's key material, as pure functions so the vectors can be tested on the JVM.
 *
 * 128-bit entropy → 12-word BIP-39 phrase (no passphrase) → BIP-32 seed → `m/44'/60'/0'/0/0`, the
 * path every Ethereum wallet imports a phrase at. That is the whole recovery story: the same 12 words
 * in MetaMask, Rabby or a hardware wallet open the same address.
 *
 * JVM limits, stated plainly: `ECKeyPair` holds its private key as a `BigInteger`, and the phrase is a
 * `String`; neither can be wiped. What can be wiped (entropy, seed) is, and every derivation is scoped
 * to one [withKeyPair] call so nothing outlives the signature it was built for.
 */
object WalletKeys {

    const val ENTROPY_BYTES = 16
    const val WORD_COUNT = 12

    private const val H = Bip32ECKeyPair.HARDENED_BIT

    /** `m/44'/60'/0'/0/0`. */
    val DERIVATION_PATH = intArrayOf(44 or H, 60 or H, 0 or H, 0, 0)

    fun phrase(entropy: ByteArray): String {
        require(entropy.size == ENTROPY_BYTES) { "expected $ENTROPY_BYTES bytes of entropy" }
        return MnemonicUtils.generateMnemonic(entropy)
    }

    fun words(entropy: ByteArray): List<String> = phrase(entropy).split(' ')

    fun <T> withKeyPair(entropy: ByteArray, block: (ECKeyPair) -> T): T {
        val seed = MnemonicUtils.generateSeed(phrase(entropy), "")
        try {
            val master = Bip32ECKeyPair.generateKeyPair(seed)
            return block(Bip32ECKeyPair.deriveKeyPair(master, DERIVATION_PATH))
        } finally {
            seed.fill(0)
        }
    }

    /** Lowercase `0x` address — the form the rest of the app keys caches and requests by. */
    fun address(entropy: ByteArray): String = withKeyPair(entropy) { address(it) }

    fun address(keyPair: ECKeyPair): String = "0x" + Keys.getAddress(keyPair).lowercase()

    /** EIP-55 form, for display only. */
    fun checksum(address: String): String = Keys.toChecksumAddress(address)

    /**
     * `eth_signTypedData_v4`: keccak256(0x1901 ‖ domainSeparator ‖ hashStruct(message)), signed with
     * recoverable secp256k1, returned as `0x` r ‖ s ‖ v with v ∈ {27, 28} — the exact shape
     * `HavenAolImpl.parseWalletSignature` accepts and the canister ecrecovers.
     */
    fun signTypedData(json: String, keyPair: ECKeyPair): String {
        val digest = StructuredDataEncoder(json).hashStructuredData()
        val sig = Sign.signMessage(digest, keyPair, false)
        return "0x" + (sig.r + sig.s + sig.v).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * The only thing this key ever signs.
 *
 * A browser wallet signs whatever a site asks; this one has exactly one caller, so it can be strict.
 * Anything that is not a Haven-AOL gate request under the `HavenAOL` domain — a Permit, a Seaport
 * order, a request for another address — is refused before the key is rebuilt. The watch holds real
 * tokens, so a compromised dependency must not be able to turn it into a general signer.
 */
object HavenTypedDataPolicy {

    private val ALLOWED_PRIMARY_TYPES = setOf("GateRequest", "GateRequestV3", "GateRequestV4", "BatchGateRequest")

    /** Throws [IllegalArgumentException] with a reason; returns normally when [json] may be signed. */
    fun check(json: String, chainId: Long, signerAddress: String) {
        val root = runCatching { JSONObject(json) }.getOrElse { throw IllegalArgumentException("not typed data") }
        val primaryType = root.optString("primaryType")
        require(primaryType in ALLOWED_PRIMARY_TYPES) { "refusing to sign $primaryType" }

        val domain = root.optJSONObject("domain") ?: throw IllegalArgumentException("no domain")
        require(domain.optString("name") == "HavenAOL") { "refusing domain ${domain.optString("name")}" }
        val domainChain = domain.opt("chainId")?.toString()?.toLongOrNull()
        require(domainChain == chainId) { "domain chain $domainChain does not match $chainId" }

        val message = root.optJSONObject("message") ?: throw IllegalArgumentException("no message")
        require(message.optString("evmAddress").equals(signerAddress, ignoreCase = true)) {
            "request is for another address"
        }
    }
}
