package finance.idem.infrastructure.security

import finance.idem.application.port.AdminTokenLockoutGuard
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * IP-keyed fixed-window lockout on failed internal-admin-token attempts (#272 review
 * finding: the per-tenant [finance.idem.infrastructure.ratelimit.RateLimitFilter] (#273)
 * explicitly excludes `/internal`, so without this the admin token has zero brute-force
 * throttling). Reuses the already-injected [StringRedisTemplate] — no new infra.
 *
 * Defense in depth alongside idem-infra's VPC/IP allowlist (not implemented in this repo,
 * see [finance.idem.api.internal.AdminTenantController]'s KDoc) — not a substitute for it.
 *
 * If a reverse proxy/load balancer sits in front of this app, the caller must supply the
 * real client IP (e.g. via a trusted `X-Forwarded-For`, resolved by idem-infra's LB
 * config), not `HttpServletRequest.remoteAddr` unmodified — flagged as an infra concern,
 * not solved here.
 */
@Component
class AdminTokenLockoutService(
    private val redisTemplate: StringRedisTemplate,
) : AdminTokenLockoutGuard {
    override fun isLockedOut(clientIp: String): Boolean =
        redisTemplate
            .opsForValue()
            .get(key(clientIp))
            ?.toIntOrNull()
            ?.let { it >= MAX_ATTEMPTS }
            ?: false

    override fun recordFailure(clientIp: String) {
        val redisKey = key(clientIp)
        val count = redisTemplate.opsForValue().increment(redisKey) ?: 1
        if (count == 1L) redisTemplate.expire(redisKey, WINDOW)
    }

    private fun key(clientIp: String) = "admin-token-lockout:$clientIp"

    companion object {
        private const val MAX_ATTEMPTS = 5
        private val WINDOW: Duration = Duration.ofMinutes(5)
    }
}
