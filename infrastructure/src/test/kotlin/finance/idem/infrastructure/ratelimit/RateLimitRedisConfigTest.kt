package finance.idem.infrastructure.ratelimit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    @Test
    fun `builds a Redis URI with no auth when no password is configured`() {
        val connectionDetails = mock<RedisConnectionDetails>()
        whenever(connectionDetails.standalone).thenReturn(RedisConnectionDetails.Standalone.of("redis.internal", 6379))
        val config = RateLimitRedisConfig(connectionDetails)

        val uri = config.buildRedisUri(connectionDetails)

        assertEquals("redis.internal", uri.host)
        assertEquals(6379, uri.port)
        assertNull(uri.username)
        assertNull(uri.password)
    }

    @Test
    fun `carries username and password onto the Redis URI when both are configured`() {
        val connectionDetails = mock<RedisConnectionDetails>()
        whenever(connectionDetails.standalone).thenReturn(RedisConnectionDetails.Standalone.of("redis.internal", 6380, 3))
        whenever(connectionDetails.username).thenReturn("ratelimit-user")
        whenever(connectionDetails.password).thenReturn("s3cret")
        val config = RateLimitRedisConfig(connectionDetails)

        val uri = config.buildRedisUri(connectionDetails)

        assertEquals("ratelimit-user", uri.username)
        assertEquals("s3cret", String(uri.password))
        assertEquals(3, uri.database)
    }

    @Test
    fun `carries a password-only credential onto the Redis URI when no username is configured`() {
        val connectionDetails = mock<RedisConnectionDetails>()
        whenever(connectionDetails.standalone).thenReturn(RedisConnectionDetails.Standalone.of("redis.internal", 6379))
        whenever(connectionDetails.username).thenReturn(null)
        whenever(connectionDetails.password).thenReturn("s3cret")
        val config = RateLimitRedisConfig(connectionDetails)

        val uri = config.buildRedisUri(connectionDetails)

        assertNull(uri.username)
        assertEquals("s3cret", String(uri.password))
    }

    @Test
    fun `carries the configured database index onto the Redis URI`() {
        val connectionDetails = mock<RedisConnectionDetails>()
        whenever(connectionDetails.standalone).thenReturn(RedisConnectionDetails.Standalone.of("redis.internal", 6379, 5))
        val config = RateLimitRedisConfig(connectionDetails)

        val uri = config.buildRedisUri(connectionDetails)

        assertEquals(5, uri.database)
    }
}
