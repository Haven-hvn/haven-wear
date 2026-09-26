package haven.wear.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the wallet lives: 16 bytes of BIP-39 entropy, AES-256-GCM encrypted under a non-exportable
 * Android Keystore key (StrongBox when the watch has one).
 *
 * - The file sits in `noBackupFilesDir`, and the manifest disables backup and device transfer, so
 *   the ciphertext never leaves the watch either. Even if it did, the Keystore key cannot.
 * - Not bound to user authentication: playback unlocks the next track in the background with the
 *   screen off, and an auth-bound key would stall it. The recovery phrase screen asks for the screen
 *   lock itself (see `RecoveryPhraseScreen`).
 * - Only entropy is stored — never the phrase, a seed or a private key. Everything else is re-derived
 *   per use and the plaintext buffer is zeroed after.
 */
@Singleton
class EntropyVault @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.noBackupFilesDir, "wallet/entropy.v1")

    fun exists(): Boolean = file.isFile && file.length() > IV_BYTES

    fun store(entropy: ByteArray) {
        check(!exists()) { "a wallet already exists on this watch" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val sealed = cipher.iv + cipher.doFinal(entropy)

        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(sealed)
        check(tmp.renameTo(file)) { "could not persist wallet" }
    }

    /** Decrypts the entropy for the duration of [block], then zeroes it. */
    fun <T> withEntropy(block: (ByteArray) -> T): T {
        val sealed = file.readBytes()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            existingKey() ?: error("wallet key is missing from the Keystore"),
            GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES),
        )
        cipher.updateAAD(AAD)
        val entropy = cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        try {
            return block(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun getOrCreateKey(): SecretKey = existingKey() ?: runCatching { generateKey(strongBox = true) }
        .recoverCatching { if (it is StrongBoxUnavailableException) generateKey(strongBox = false) else throw it }
        .getOrThrow()

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "haven.wear.wallet.entropy.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val AAD = "haven-wear:entropy:v1".toByteArray(Charsets.UTF_8)
    }
}
