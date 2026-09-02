package finance.idem.infrastructure.ratelimit

import finance.idem.core.TenantId
import finance.idem.infrastructure.security.ApiKeyAuthentication
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.security.core.context.SecurityContextHolder
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.assertTrue

class RateLimitFilterTest {
    private val rateLimiterService = mock<RateLimiterService>()
    private val filter = RateLimitFilter(rateLimiterService)
    private val chain = mock<FilterChain>()

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `excluded path skips the rate limiter and calls through without touching security context`() {
        val request = requestFor("/actuator/health")
        val response = mock<HttpServletResponse>()

        filter.doFilter(request, response, chain)

        verifyNoInteractions(rateLimiterService)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `internal path is excluded`() {
        val request = requestFor("/internal/webhooks/alchemy")
        val response = mock<HttpServletResponse>()

        filter.doFilter(request, response, chain)

        verifyNoInteractions(rateLimiterService)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `exact excluded path with no trailing segment is excluded`() {
        val request = requestFor("/actuator")
        val response = mock<HttpServletResponse>()

        filter.doFilter(request, response, chain)

        verifyNoInteractions(rateLimiterService)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `a lookalike path sharing only the prefix text is NOT excluded`() {
        val tenantId = authenticate()
        whenever(rateLimiterService.tryConsume(tenantId)).thenReturn(RateLimitResult.Allowed)
        val request = requestFor("/actuatorless-report")
        val response = mock<HttpServletResponse>()

        filter.doFilter(request, response, chain)

        verify(rateLimiterService).tryConsume(tenantId)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `no tenant resolved on the security context passes through untouched`() {
        val request = requestFor("/api/v1/transactions")
        val response = mock<HttpServletResponse>()

        filter.doFilter(request, response, chain)

        verifyNoInteractions(rateLimiterService)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `Unlimited result calls through the chain`() {
        val tenantId = authenticate()
        whenever(rateLimiterService.tryConsume(tenantId)).thenReturn(RateLimitResult.Unlimited)
        val request = requestFor("/api/v1/transactions")
        val response = mock<HttpServletResponse>()

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    @Test
    fun `Allowed result calls through the chain`() {
        val tenantId = authenticate()
        whenever(rateLimiterService.tryConsume(tenantId)).thenReturn(RateLimitResult.Allowed)
        val request = requestFor("/api/v1/transactions")
        val response = mock<HttpServletResponse>()

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    @Test
    fun `Denied result writes 429, Retry-After header, and JSON body without calling the chain`() {
        val tenantId = authenticate()
        whenever(rateLimiterService.tryConsume(tenantId)).thenReturn(RateLimitResult.Denied(retryAfterSeconds = 7))
        val request = requestFor("/api/v1/transactions")
        val response = mock<HttpServletResponse>()
        val stringWriter = StringWriter()
        whenever(response.writer).thenReturn(PrintWriter(stringWriter))

        filter.doFilter(request, response, chain)

        verify(response).status = 429
        verify(response).setHeader("Retry-After", "7")
        verify(chain, never()).doFilter(any(), any())
        assertTrue(stringWriter.toString().contains("rate_limit_exceeded"))
        assertTrue(stringWriter.toString().contains("7s"))
    }

    @Test
    fun `an exception from the rate limiter fails open — chain is called, no 429`() {
        val tenantId = authenticate()
        whenever(rateLimiterService.tryConsume(tenantId)).thenThrow(RuntimeException("Redis unreachable"))
        val request = requestFor("/api/v1/transactions")
        val response = mock<HttpServletResponse>()

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        verify(response, never()).status = eq(429)
    }

    private fun authenticate(): TenantId {
        val tenantId = TenantId.generate()
        SecurityContextHolder.getContext().authentication = ApiKeyAuthentication(tenantId, "sk_live_abcd", emptyList())
        return tenantId
    }

    private fun requestFor(uri: String): HttpServletRequest =
        mock<HttpServletRequest>().also {
            whenever(it.requestURI).thenReturn(uri)
        }
}
