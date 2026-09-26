package haven.mobile.core.crypto

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AesKeyCache(private val capacity: Int = 256) {
    private val cache = LinkedHashMap<String, ByteArray>(capacity, 0.75f, true)
    private val mutex = Mutex()

    fun put(pieceCid: String, key: ByteArray) {
        runBlocking {
            mutex.withLock {
                if (cache.size >= capacity) {
                    val eldest = cache.entries.first()
                    zeroize(eldest.value)
                    cache.remove(eldest.key)
                }
                cache[pieceCid] = key.copyOf()
            }
        }
    }

    fun get(pieceCid: String): ByteArray? {
        return runBlocking {
            mutex.withLock {
                cache[pieceCid]?.copyOf()
            }
        }
    }

    suspend fun getSuspend(pieceCid: String): ByteArray? {
        return mutex.withLock { cache[pieceCid]?.copyOf() }
    }

    suspend fun putSuspend(pieceCid: String, key: ByteArray) {
        mutex.withLock {
            if (cache.size >= capacity) {
                val eldest = cache.entries.first()
                zeroize(eldest.value)
                cache.remove(eldest.key)
            }
            cache[pieceCid] = key.copyOf()
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

    suspend fun clearAllSuspend() {
        mutex.withLock {
            for (entry in cache.entries) {
                zeroize(entry.value)
            }
            cache.clear()
        }
    }

    private fun zeroize(bytes: ByteArray) {
        for (i in bytes.indices) {
            bytes[i] = 0
        }
    }
}
