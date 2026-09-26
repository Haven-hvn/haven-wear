package haven.mobile.core.haven.aol.vetkeys

/**
 * Device side of the VetKD unwrap: ephemeral transport keys plus sealed-key recovery.
 *
 * An interface (rather than direct JNI calls) so JVM tests run without the native library:
 * production binds [JniVetKdUnwrap], tests fake this. Secrets cross as byte arrays and are
 * zeroed after use; the native side wipes its copies too.
 */
interface VetKdUnwrap {

    /** False when `libhaven_vetkeys.so` is not in this build (no NDK step ran). */
    fun isAvailable(): Boolean

    /**
     * Ephemeral transport keypair for one unwrap — dapp parity with `createTransportKeyPair`.
     * The public half travels to the canister; the secret half stays here for [unwrapContentKey].
     */
    fun generateTransportKeypair(): Result<TransportKeypair>

    /**
     * Full unwrap — dapp parity with `recoverVetKey` + `ibeDecryptAesKey` fused into one call
     * so the intermediate VetKey never leaves native memory. Returns the 32-byte AES content
     * key. The transport secret in [params] is zeroed before this returns, success or failure.
     */
    fun unwrapContentKey(params: UnwrapParams): Result<ByteArray>
}

/** One ephemeral transport keypair: canister-bound public half, device-held secret half. */
data class TransportKeypair(
    val publicKey: ByteArray,
    val secretKey: ByteArray,
)

/** Everything [VetKdUnwrap.unwrapContentKey] needs after the canister round-trip. */
data class UnwrapParams(
    /** Canister reply `encrypted_key`. */
    val encryptedVetKey: ByteArray,
    /** Live half of [TransportKeypair]; zeroed after the call. */
    val transportSecret: ByteArray,
    /** Canister reply `verification_key` (bundled — no extra round-trip). */
    val verificationKey: ByteArray,
    /** `SHA-256("accessol:{chain}:{token}:{threshold}:{cid}")`, 32 bytes. */
    val derivationInput: ByteArray,
    /** Gate record `encryptedAesKey`, base64 text as UTF-8 bytes. */
    val sealedKeyUtf8: ByteArray,
)
