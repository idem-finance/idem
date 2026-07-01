package finance.idem.infrastructure.outbox

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryScheduleTest {
    @Test
    fun `attempt 1 retries after 5 seconds`() {
        assertEquals(Duration.ofSeconds(5), RetrySchedule.nextRetryDelay(1, maxAttempts = 5))
    }

    @Test
    fun `attempt 2 retries after 30 seconds`() {
        assertEquals(Duration.ofSeconds(30), RetrySchedule.nextRetryDelay(2, maxAttempts = 5))
    }

    @Test
    fun `attempt 3 retries after 2 minutes`() {
        assertEquals(Duration.ofMinutes(2), RetrySchedule.nextRetryDelay(3, maxAttempts = 5))
    }

    @Test
    fun `attempt 4 retries after 10 minutes`() {
        assertEquals(Duration.ofMinutes(10), RetrySchedule.nextRetryDelay(4, maxAttempts = 5))
    }

    @Test
    fun `attempt 5 has no further retry -- caller marks DEAD`() {
        assertNull(RetrySchedule.nextRetryDelay(5, maxAttempts = 5))
    }

    @Test
    fun `attempts beyond maxAttempts have no further retry`() {
        assertNull(RetrySchedule.nextRetryDelay(6, maxAttempts = 5))
    }

    @Test
    fun `MAX_SUPPORTED_ATTEMPTS reflects the backoff table size`() {
        assertEquals(5, RetrySchedule.MAX_SUPPORTED_ATTEMPTS)
    }
}
