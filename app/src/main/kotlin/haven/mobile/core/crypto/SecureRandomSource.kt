package haven.mobile.core.crypto

import java.security.SecureRandom

class SecureRandomSource {
    private val delegate: SecureRandom = SecureRandom.getInstanceStrong()

    fun nextBytes(bytes: ByteArray) {
        delegate.nextBytes(bytes)
    }
}