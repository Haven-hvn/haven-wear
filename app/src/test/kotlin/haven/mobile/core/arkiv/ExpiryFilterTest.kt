package haven.mobile.core.arkiv

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Active means at least one non-expired entity: expiry is `expiresAtBlock` against the
 * query head, never a status the entity carries. These pin the rule without network.
 */
class ExpiryFilterTest {

    private val client = ArkivClientImpl(ArkivConfig(endpointUrl = "https://example.com"))

    @Test
    fun `expired at or below head`() {
        with(client) {
            assertTrue(isExpired(100, 100))
            assertTrue(isExpired(90, 100))
        }
    }

    @Test
    fun `live above head`() {
        with(client) {
            assertFalse(isExpired(101, 100))
        }
    }

    @Test
    fun `null expiry is active`() {
        with(client) {
            assertFalse(isExpired(null, 100))
        }
    }

    @Test
    fun `null head fails open to active`() {
        with(client) {
            assertFalse(isExpired(90, null))
        }
    }

    @Test
    fun `head parses hex quantity`() {
        with(client) {
            assertEquals(16L, headBlockOrNull(JSONObject("""{"blockNumber":"0x10"}""")))
        }
    }

    @Test
    fun `head parses decimal`() {
        with(client) {
            assertEquals(42L, headBlockOrNull(JSONObject("""{"blockNumber":42}""")))
        }
    }

    @Test
    fun `missing head reads null`() {
        with(client) {
            assertNull(headBlockOrNull(JSONObject("""{"data":[]}""")))
        }
    }
}
