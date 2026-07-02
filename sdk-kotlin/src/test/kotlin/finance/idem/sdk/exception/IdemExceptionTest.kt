package finance.idem.sdk.exception

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdemExceptionTest {
    @Test
    fun `ApiException traceId defaults to null when not provided`() {
        val exception = ApiException(statusCode = 404, errorCode = "NOT_FOUND", message = "Resource not found")

        assertNull(exception.traceId)
        assertEquals(404, exception.statusCode)
        assertEquals("NOT_FOUND", exception.errorCode)
    }

    @Test
    fun `RateLimitException traceId defaults to null when not provided`() {
        val exception = RateLimitException(retryAfterSeconds = 5)

        assertNull(exception.traceId)
        assertEquals(5, exception.retryAfterSeconds)
        assertEquals("Rate limit exceeded; retry after 5 seconds", exception.message)
    }

    @Test
    fun `NetworkException traceId is always null`() {
        val cause = RuntimeException("Connection refused")
        val exception = NetworkException(cause)

        assertNull(exception.traceId)
        assertEquals(cause, exception.cause)
    }
}
