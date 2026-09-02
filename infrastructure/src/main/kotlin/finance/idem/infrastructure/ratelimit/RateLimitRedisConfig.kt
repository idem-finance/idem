package finance.idem.infrastructure.ratelimit

import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Builds the raw Lettuce [RedisClient]/[LettuceBasedProxyManager] that bucket4j's Redis
 * integration needs directly — Spring Data's `RedisConnectionFactory` abstraction doesn't
 * expose the [io.lettuce.core.api.StatefulRedisConnection] bucket4j requires.
 *
 * Reads [RedisConnectionDetails] rather than `spring.data.redis.host`/`port` `@Value`s
 * directly: that's the same Spring Boot-managed abstraction [org.springframework.data.redis.core.StringRedisTemplate]'s
 * autoconfiguration resolves against, and — critically — it's what a test's
 * `@ServiceConnection` Testcontainers wiring overrides. A plain `@Value` lookup would ignore
 * `@ServiceConnection` and always fall back to its literal default, silently connecting to
 * the wrong host/port under Testcontainers-backed tests.
 *
 * Gated behind `idem.ratelimit.enabled` so a disabled (self-hosted default) install never
 * opens a second Redis connection.
 */
@Configuration
@ConditionalOnProperty(name = ["idem.ratelimit.enabled"], havingValue = "true")
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitRedisConfig(
    private val connectionDetails: RedisConnectionDetails,
) {
    @Bean(destroyMethod = "shutdown")
    fun bucket4jRedisClient(): RedisClient {
        val standalone =
            requireNotNull(connectionDetails.standalone) {
                "RateLimitRedisConfig only supports standalone Redis (got sentinel/cluster connection details)"
            }
        return RedisClient.create(RedisURI.Builder.redis(standalone.host, standalone.port).build())
    }

    @Bean
    fun rateLimitProxyManager(redisClient: RedisClient): LettuceBasedProxyManager<ByteArray> =
        Bucket4jLettuce.casBasedBuilder(redisClient).build()
}
