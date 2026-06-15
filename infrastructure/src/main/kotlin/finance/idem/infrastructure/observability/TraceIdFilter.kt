package finance.idem.infrastructure.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Generates a per-request trace/correlation ID, exposes it via the [TRACE_ID_HEADER]
 * response header, and binds it to MDC (key [MDC_KEY]) for the duration of the request
 * so it appears in every log line.
 *
 * Registered before [finance.idem.infrastructure.security.ApiKeyAuthFilter] so the header
 * and MDC context are present even for unauthenticated (401) responses.
 *
 * Header name mirrored in idem-sdk-kotlin's IdemClient.handleResponse (sdk-kotlin has zero
 * deps on this module, so the literal is duplicated there with a cross-reference comment).
 */
class TraceIdFilter : OncePerRequestFilter() {

    companion object {
        const val TRACE_ID_HEADER = "X-Idem-Trace-Id"
        const val MDC_KEY = "traceId"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val traceId = UUID.randomUUID().toString()
        response.setHeader(TRACE_ID_HEADER, traceId)
        MDC.put(MDC_KEY, traceId)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }
}
