package finance.idem.infrastructure.ratelimit

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = ["idem.ratelimit.enabled"], havingValue = "true")
class RateLimitFilterConfig(
    private val rateLimiterService: RateLimiterService,
) {
    @Bean
    fun rateLimitFilter(): RateLimitFilter = RateLimitFilter(rateLimiterService)
}
