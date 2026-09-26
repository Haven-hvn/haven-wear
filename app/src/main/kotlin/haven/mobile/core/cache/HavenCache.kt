package haven.mobile.core.cache

import cloud.filecoin.foc.cache.PieceRef
import cloud.filecoin.foc.cache.PieceTransform
import kotlinx.coroutines.flow.Flow
import java.io.File

interface HavenCache {
    suspend fun get(ref: PieceRef): Result<ByteArray>
    fun stream(ref: PieceRef): Flow<ByteArray>
    /**
     * The piece as a local file; on a miss it's fetched and [transform]ed once, and the output is
     * what's kept. Use one transform per piece consistently (see foc's `PieceTransform`).
     */
    suspend fun file(ref: PieceRef, transform: PieceTransform? = null): Result<File>
    suspend fun exists(pieceCid: String): Boolean
    suspend fun fetch(ref: PieceRef): Result<Unit>
    suspend fun remove(pieceCid: String)
    suspend fun space(): CacheSpace
    suspend fun clearFor(walletAddress: String)
    suspend fun clearExpiredFor(walletAddress: String)
}