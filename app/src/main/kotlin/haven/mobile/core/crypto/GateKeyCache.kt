package haven.mobile.core.crypto

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GateKeyCache(private val capacity: Int = 64) {
    private val cache = LinkedHashMap<String, ByteArray>(capacity, 0.75f, true)
    private val mutex = Mutex()

    fun put(gateKey: String, key: ByteArray) {
        runBlocking {
            mutex.withLock {
                if (cache.size >= capacity) {
                    val eldest = cache.entries.first()
                    zeroize(eldest.value)
                    cache.remove(eldest.key)
                }
                cache[gateKey] = key.copyOf()
            }
        }
    }

    fun get(gateKey: String): ByteArray? {
        return runBlocking {
            mutex.withLock {
                cache[gateKey]?.copyOf()
            }
        }
    }

    fun clearAll() {
        runBlocking {
            mutex.withLock {
                for (entry in cache.entries) {
                    zeroize(entry.value)
                }
                cache.clear()
            }
        }
    }

    private fun zeroize(bytes: ByteArray) {
        for (i in bytes.indices) {
            bytes[i] = 0
        }
    }
}
