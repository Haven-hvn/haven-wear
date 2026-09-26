package haven.mobile.core.domain.error

// Mirrors stable error codes from haven-dapp-main/src/lib/cache-errors.ts and playback-errors.ts
sealed class HavenError(
    open val code: String,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {
    // Mirrors cache-errors.ts::CACHE_QUOTA_EXCEEDED
    class CacheQuotaExceeded(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("CACHE_QUOTA_EXCEEDED", message, cause)

    // Mirrors cache-errors.ts::CACHE_MISS
    class CacheMiss(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("CACHE_MISS", message, cause)

    // Mirrors cache-errors.ts::CACHE_STALE
    class CacheStale(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("CACHE_STALE", message, cause)

    // Mirrors cache-errors.ts::CACHE_WRITE_FAILED
    class CacheWriteFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("CACHE_WRITE_FAILED", message, cause)

    // Mirrors cache-errors.ts::CACHE_READ_FAILED
    class CacheReadFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("CACHE_READ_FAILED", message, cause)

    // Mirrors cache-errors.ts::CACHE_PROVIDER_ERROR
    class CacheProviderError(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("CACHE_PROVIDER_ERROR", message, cause)

    // Mirrors cache-errors.ts::CACHE_PIECE_VERIFY_FAILED
    class CachePieceVerifyFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("CACHE_PIECE_VERIFY_FAILED", message, cause)

    // Mirrors cache-errors.ts::ALL_PROVIDERS_CORRUPT
    class AllProvidersCorrupt(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("ALL_PROVIDERS_CORRUPT", message, cause)

    // Mirrors playback-errors.ts::PLAYBACK_DECRYPT_FAILED
    class PlaybackDecryptFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("PLAYBACK_DECRYPT_FAILED", message, cause)

    // Mirrors playback-errors.ts::PLAYBACK_CODEC_UNSUPPORTED
    class PlaybackCodecUnsupported(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("PLAYBACK_CODEC_UNSUPPORTED", message, cause)

    // Mirrors playback-errors.ts::PLAYBACK_STREAM_ERROR
    class PlaybackStreamError(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("PLAYBACK_STREAM_ERROR", message, cause)

    // Mirrors network error constant from cache-errors.ts
    class NetworkError(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("NETWORK_ERROR", message, cause)

    // Mirrors canister call error from cache-errors.ts
    class CanisterCallFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("CANISTER_CALL_FAILED", message, cause)

    // Mirrors signing error from cache-errors.ts
    class SigningFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("SIGNING_FAILED", message, cause)

    // Mirrors gate verification error from cache-errors.ts
    class GateVerificationFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("GATE_VERIFICATION_FAILED", message, cause)

    // Mirrors attestation error from cache-errors.ts
    class AttestationFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("ATTESTATION_FAILED", message, cause)

    // Mirrors key error from cache-errors.ts
    class NoKeyAvailable(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("NO_KEY_AVAILABLE", message, cause)

    // Mirrors wallet error from cache-errors.ts
    class WalletNotConnected(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("WALLET_NOT_CONNECTED", message, cause)

    // Mirrors gate metadata error from cache-errors.ts
    class UnsupportedGateMetadata(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("UNSUPPORTED_GATE_METADATA", message, cause)

    // Mirrors signature error from cache-errors.ts
    class InvalidSignatureFormat(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("INVALID_SIGNATURE_FORMAT", message, cause)

    // Mirrors appkit error from cache-errors.ts
    class AppKitNotInitialized(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("APPKIT_NOT_INITIALIZED", message, cause)

    // Mirrors address error from cache-errors.ts
    class NoAddressReturned(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("NO_ADDRESS_RETURNED", message, cause)

    // Mirrors connect error from cache-errors.ts
    class ConnectFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("CONNECT_FAILED", message, cause)

    /**
     * gate_type 4: the gate token's market cap has not reached this drip stage's target yet.
     * Both values in whole reserve units (whole ETH for native-reserve Bond tokens), as the
     * canister reports them in `#MarketCapNotReached`. Not a wallet or access problem —
     * the stage opens for every holder once the community pumps the token.
     */
    class MarketCapNotReached(
        val required: java.math.BigInteger,
        val actual: java.math.BigInteger,
        override val message: String = "This premiere unlocks when the gate token's market cap reaches its target.",
        override val cause: Throwable? = null
    ) : HavenError("MARKET_CAP_NOT_REACHED", message, cause)

    // Mirrors internal error from cache-errors.ts
    class Internal(
        override val message: String,
        override val cause: Throwable? = null
    ) : HavenError("INTERNAL", message, cause)
}
