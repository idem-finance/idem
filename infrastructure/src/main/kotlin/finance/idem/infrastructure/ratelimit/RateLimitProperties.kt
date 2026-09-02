package finance.idem.infrastructure.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Off by default (`enabled = false`) — self-hosted/open-source installs never rate-limit
 * requests. The Cloud SaaS deployment enables this via `IDEM_RATELIMIT_ENABLED=true`.
 * The two default fields are the CLOUD-plan fallback used by [finance.idem.core.tenant.RateLimitPolicy]
 * when a tenant hasn't configured its own `rateLimitPerSecond`/`rateLimitPerMinute`.
 */
@ConfigurationProperties("idem.ratelimit")
data class RateLimitProperties(
    val enabled: Boolean = false,
    val cloudDefaultPerSecond: Int = 100,
    val cloudDefaultPerMinute: Int = 1000,
)
