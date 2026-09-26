package haven.mobile.core.haven.aol.vetkeys

import javax.inject.Inject

/**
 * Raw JNI boundary to `libhaven_vetkeys.so` (see `src/main/rust/haven_vetkeys`).
 *
 * A method-name contract with the Rust exports — `nativeTransportKeypair` and
 * `nativeUnwrapContentKey` must stay `@JvmStatic` here or the linker names stop matching.
 * Never called directly outside [JniVetKdUnwrap]: nulls and thrown messages translate to
 * [Result] there.
 */
internal object VetKeysNative {

    /** False when the build skipped the NDK step; unlock degrades to a clear message. */
    val isAvailable: Boolean = try {
        System.loadLibrary("haven_vetkeys")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** `[transportPublicKey(48B), transportSecret(32B)]`; throws `IllegalStateException`. */
    @JvmStatic
    external fun nativeTransportKeypair(): Array<ByteArray?>

    /** 32-byte AES key, or a thrown `IllegalStateException` carrying the Rust error. */
    @JvmStatic
    external fun nativeUnwrapContentKey(
        encVetKey: ByteArray,
        transportSecret: ByteArray,
        verificationKey: ByteArray,
        derivationInput: ByteArray,
        sealedKeyUtf8: ByteArray,
    ): ByteArray?
}

/** Production [VetKdUnwrap]: thin [Result] skin over [VetKeysNative]. */
class JniVetKdUnwrap @Inject constructor() : VetKdUnwrap {

    override fun isAvailable(): Boolean = VetKeysNative.isAvailable

    override fun generateTransportKeypair(): Result<TransportKeypair> {
        if (!VetKeysNative.isAvailable) return Result.failure(nativeMissing())
        return runCatching {
            val pair = VetKeysNative.nativeTransportKeypair()
            TransportKeypair(
                publicKey = pair.getOrNull(0)
                    ?: throw IllegalStateException("native keypair missing public key"),
                secretKey = pair.getOrNull(1)
                    ?: throw IllegalStateException("native keypair missing secret"),
            )
        }
    }

    override fun unwrapContentKey(params: UnwrapParams): Result<ByteArray> {
        if (!VetKeysNative.isAvailable) return Result.failure(nativeMissing())
        return runCatching {
            VetKeysNative.nativeUnwrapContentKey(
                params.encryptedVetKey,
                params.transportSecret,
                params.verificationKey,
                params.derivationInput,
                params.sealedKeyUtf8,
            ) ?: throw IllegalStateException("native unwrap returned nothing")
        }.also {
            params.transportSecret.fill(0)
        }
    }

    private fun nativeMissing(): IllegalStateException =
        IllegalStateException("haven_vetkeys is not in this build (missing NDK step)")
}
