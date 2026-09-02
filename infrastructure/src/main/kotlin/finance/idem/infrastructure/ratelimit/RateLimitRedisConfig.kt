package finance.idem.infrastructure.ratelimit

import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Builds the raw Lettuce [RedisClient]/[LettuceBasedProxyManager] that bucket4j's Redis
 * integration needs directly — Spring Data's `RedisConnectionFactory` abstraction doesn't
 * expose the [io.lettuce.core.api.StatefulRedisConnection] bucket4j requires, so this reads
 * the same `spring.data.redis.host`/`port` properties Spring Boot already autoconfigures
 * [org.springframework.data.redis.core.StringRedisTemplate] from, rather than introducing a
 * second configuration surface.
 *
 * Gated behind `idem.ratelimit.enabled` so a disabled (self-hosted default) install never
 * opens a second Redis connection.
 */
@Configuration
@ConditionalOnProperty(name = ["idem.ratelimit.enabled"], havingValue = "true")
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitRedisConfig(
    @Value("\${spring.data.redis.host:localhost}") private val host: String,
    @Value("\${spring.data.redis.port:6379}") private val port: Int,
) {
    @Bean(destroyMethod = "shutdown")
    fun bucket4jRedisClient(): RedisClient = RedisClient.create(RedisURI.Builder.redis(host, port).build())

    @Bean
    fun rateLimitProxyManager(redisClient: RedisClient): LettuceBasedProxyManager<ByteArray> =
        Bucket4jLettuce.casBasedBuilder(redisClient).build()
}
