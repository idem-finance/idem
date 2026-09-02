package finance.idem.infrastructure.ratelimit

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

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
 * the wrong host/port under Testcontainers-backed tests. It also carries the username/password/
 * database that a bare host/port `RedisURI` would silently drop — required against any Redis
 * that enforces AUTH (e.g. GCP Memorystore), or every command fails and `RateLimitFilter`'s
 * fail-open handling means rate limiting silently never enforces.
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
    companion object {
        // Once a tenant's bucket has been idle long enough that it would have refilled to
        // full anyway, the key's persisted (possibly stale) state is worthless — expire it
        // rather than let every tenant that ever made one request accumulate a Redis key
        // forever.
        private val BUCKET_MAX_UNUSED_TIME = Duration.ofMinutes(10)
    }

    @Bean(destroyMethod = "shutdown")
    fun bucket4jRedisClient(): RedisClient = RedisClient.create(buildRedisUri(connectionDetails))

    internal fun buildRedisUri(connectionDetails: RedisConnectionDetails): RedisURI {
        val standalone =
            requireNotNull(connectionDetails.standalone) {
                "RateLimitRedisConfig only supports standalone Redis (got sentinel/cluster connection details)"
            }
        val uriBuilder =
            RedisURI.Builder
                .redis(standalone.host, standalone.port)
                .withDatabase(standalone.database)
        connectionDetails.password?.let { password ->
            val username = connectionDetails.username
            if (username != null) {
                uriBuilder.withAuthentication(username, password)
            } else {
                uriBuilder.withPassword(password)
            }
        }
        return uriBuilder.build()
    }

    @Bean
    fun rateLimitProxyManager(redisClient: RedisClient): LettuceBasedProxyManager<ByteArray> =
        Bucket4jLettuce
            .casBasedBuilder(redisClient)
            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(BUCKET_MAX_UNUSED_TIME))
            .build()
}
