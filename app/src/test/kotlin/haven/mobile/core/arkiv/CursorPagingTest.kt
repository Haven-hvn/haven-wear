package haven.mobile.core.arkiv

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Paging ends when the node stops sending a cursor — which is every small library's single
 * page. The missing key reads null through a platform type that compiles a direct call, so a
 * bare dereference NPEs there and once blanked all three tabs behind a generic offline error.
 * These pin the terminal-page rule without touching the network.
 */
class CursorPagingTest {

    private val client = ArkivClientImpl(ArkivConfig(endpointUrl = "https://example.com"))

    private fun next(resultJson: String, received: Int, pageSize: Int = 20) = with(client) {
        nextCursorOrNull(JSONObject(resultJson), received, pageSize)
    }

    @Test
    fun `missing cursor ends iteration instead of throwing`() {
        assertNull(next("""{"data":[]}""", received = 0))
    }

    @Test
    fun `empty cursor ends iteration`() {
        assertNull(next("""{"data":[],"cursor":""}""", received = 0))
    }

    @Test
    fun `short page ends iteration even with a cursor`() {
        assertNull(next("""{"data":[],"cursor":"opaque"}""", received = 3))
    }

    @Test
    fun `full page keeps its cursor`() {
        assertEquals("opaque", next("""{"data":[],"cursor":"opaque"}""", received = 20))
    }
}
