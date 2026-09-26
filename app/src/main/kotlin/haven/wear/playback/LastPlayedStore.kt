package haven.wear.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last queue, for the Tile and for resuming after the process is gone. Media ids and display
 * text only — public metadata, nothing decrypted.
 */
@Singleton
class LastPlayedStore @Inject constructor(@ApplicationContext context: Context) {

    data class LastPlayed(
        val queue: List<String>,
        val index: Int,
        val positionMs: Long,
        val title: String,
        val artist: String,
    )

    private val prefs = context.getSharedPreferences("last_played", Context.MODE_PRIVATE)

    fun save(value: LastPlayed) {
        prefs.edit()
            .putString(KEY_QUEUE, JSONArray(value.queue).toString())
            .putInt(KEY_INDEX, value.index)
            .putLong(KEY_POSITION, value.positionMs)
            .putString(KEY_TITLE, value.title)
            .putString(KEY_ARTIST, value.artist)
            .apply()
    }

    fun read(): LastPlayed? {
        val queueJson = prefs.getString(KEY_QUEUE, null) ?: return null
        val queue = runCatching { JSONArray(queueJson).let { a -> (0 until a.length()).map { a.getString(it) } } }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return LastPlayed(
            queue = queue,
            index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, queue.lastIndex),
            positionMs = prefs.getLong(KEY_POSITION, 0L),
            title = prefs.getString(KEY_TITLE, null).orEmpty(),
            artist = prefs.getString(KEY_ARTIST, null).orEmpty(),
        )
    }

    private companion object {
        const val KEY_QUEUE = "queue"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
    }
}
