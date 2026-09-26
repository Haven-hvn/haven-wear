package haven.mobile.core.crypto

data class CryptoConfig(
    val aesKeyCacheCapacity: Int = 256,
    val gateKeyCacheCapacity: Int = 64
)