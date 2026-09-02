package finance.idem.infrastructure.ratelimit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails
import kotlin.test.assertNotNull

class RateLimitRedisConfigTest {
    @Test
    fun `builds a Redis client from standalone connection details`() {
        val connectionDetails = mock<RedisConnectionDetails>()
        whenever(connectionDetails.standalone).thenReturn(RedisConnectionDetails.Standalone.of("localhost", 6379))
        val config = RateLimitRedisConfig(connectionDetails)

        val client = config.bucket4jRedisClient()

        assertNotNull(client)
        client.shutdown()
    }

    @Test
    fun `throws when connection details have no standalone configuration`() {
        val connectionDetails = mock<RedisConnectionDetails>()
        whenever(connectionDetails.standalone).thenReturn(null)
        val config = RateLimitRedisConfig(connectionDetails)

        assertThrows<IllegalArgumentException> { config.bucket4jRedisClient() }
    }
}
