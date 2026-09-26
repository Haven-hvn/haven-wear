package haven.wear.wallet

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import haven.mobile.core.domain.error.HavenError
import haven.mobile.core.wallet.WalletSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.web3j.crypto.MnemonicUtils
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The watch's own wallet, behind the same [WalletSession] the phone's Reown session implements —
 * so the ported unlock core (`HavenAolImpl`) runs unchanged and never knows which it has.
 *
 * Differences from the phone, all deliberate:
 * - There is no "connect": the wallet is created silently on first launch and is simply there.
 * - Signing is local and silent. The only thing it will sign is a Haven gate request for its own
 *   address ([HavenTypedDataPolicy]); nothing else ever gets as far as the key.
 * - It never sends transactions, so it never needs ETH for gas.
 */
@Singleton
class WatchWallet @Inject constructor(
    @ApplicationContext context: Context,
    private val vault: EntropyVault,
) : WalletSession {

    // The address is public; caching it lets the first frame show it without touching the Keystore.
    private val prefs = context.getSharedPreferences("wallet", Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val _address = MutableStateFlow(prefs.getString(KEY_ADDRESS, null))
    override val address: StateFlow<String?> = _address.asStateFlow()

    override val diagnostics: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val pairingUri: StateFlow<String?> = MutableStateFlow(null)

    /** Creates the wallet if this is the first launch, and returns its address. Idempotent. */
    suspend fun ensureCreated(): String = withContext(Dispatchers.Default) {
        lock.withLock {
            if (!vault.exists()) {
                val entropy = ByteArray(WalletKeys.ENTROPY_BYTES).also { SecureRandom().nextBytes(it) }
                try {
                    vault.store(entropy)
                } finally {
                    entropy.fill(0)
                }
            }
            val derived = vault.withEntropy { WalletKeys.address(it) }
            if (derived != _address.value) {
                prefs.edit().putString(KEY_ADDRESS, derived).apply()
                _address.value = derived
            }
            derived
        }
    }

    override suspend fun connect(): Result<String> = runCatching { ensureCreated() }

    /** The watch wallet is permanent; there is nothing to disconnect. */
    override suspend fun disconnect() = Unit

    override suspend fun signTypedDataV4(json: String, chainId: Long): Result<String> =
        withContext(Dispatchers.Default) {
            runCatching {
                val signer = address.value ?: ensureCreated()
                runCatching { HavenTypedDataPolicy.check(json, chainId, signer) }
                    .getOrElse { throw HavenError.SigningFailed("This watch only signs Haven unlock requests.", it) }
                vault.withEntropy { entropy -> WalletKeys.withKeyPair(entropy) { WalletKeys.signTypedData(json, it) } }
            }
        }

    override suspend fun sendTransaction(to: String, data: String, chainId: Long, valueHex: String): Result<String> =
        Result.failure(HavenError.SigningFailed("This watch doesn't send transactions."))

    /**
     * The 12 recovery words. Callers must have confirmed the screen lock first and must keep the
     * result on a FLAG_SECURE screen, off the clipboard, out of logs and off the Data Layer.
     */
    suspend fun recoveryWords(): List<String> = withContext(Dispatchers.Default) {
        vault.withEntropy { entropy ->
            WalletKeys.words(entropy).also { check(MnemonicUtils.validateMnemonic(it.joinToString(" "))) }
        }
    }

    private companion object {
        const val KEY_ADDRESS = "address"
    }
}
