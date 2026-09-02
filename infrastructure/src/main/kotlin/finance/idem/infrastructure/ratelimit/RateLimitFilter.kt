package finance.idem.infrastructure.ratelimit

import finance.idem.infrastructure.security.ApiKeyAuthentication
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Enforces the per-tenant Redis-backed token bucket (#273) via [RateLimiterService].
 *
 * Registered after [finance.idem.infrastructure.security.ApiKeyAuthFilter] — it needs the
 * tenant ID that filter resolves onto [SecurityContextHolder]. Requests with no resolved
 * tenant (unauthenticated) pass through untouched; the downstream 401 handles those.
 *
 * Fails open on any unexpected error (e.g. Redis outage): a rate-limiting hiccup must never
 * become a full outage for paying Cloud tenants, mirroring [finance.idem.infrastructure.security.ApiKeyAuthFilter]'s
 * degrade-safely posture for auth failures.
 */
class RateLimitFilter(
    private val rateLimiterService: RateLimiterService,
) : OncePerRequestFilter() {
    companion object {
        private val EXCLUDED_PREFIXES = listOf("/actuator", "/internal")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (EXCLUDED_PREFIXES.any { request.requestURI.startsWith(it) }) {
            chain.doFilter(request, response)
            return
        }

        val tenantId = (SecurityContextHolder.getContext().authentication as? ApiKeyAuthentication)?.tenantId
        if (tenantId == null) {
            chain.doFilter(request, response)
            return
        }

        val result =
            runCatching { rateLimiterService.tryConsume(tenantId) }
                .getOrElse { e ->
                    logger.error("Rate limit check failed unexpectedly (Redis outage?); failing open", e)
                    RateLimitResult.Allowed
                }

        if (result is RateLimitResult.Denied) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.setHeader("Retry-After", result.retryAfterSeconds.toString())
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write(
                """{"code":"rate_limit_exceeded","message":"Too many requests, retry after ${result.retryAfterSeconds}s"}""",
            )
        } else {
            chain.doFilter(request, response)
        }
    }
}
