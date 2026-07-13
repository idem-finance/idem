package finance.idem.infrastructure.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class ApiKeyAuthFilter(
    private val apiKeyService: ApiKeyService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        extractRawKey(request)?.let { rawKey ->
            // Isolate validation failures (e.g. Redis outage, cache deserialization error):
            // degrade to unauthenticated so the request gets a clean 401 from the
            // authentication entry point rather than a 500 leaking from this filter.
            val validated =
                runCatching { apiKeyService.validate(rawKey) }
                    .getOrElse { e ->
                        logger.error("API key validation failed unexpectedly; treating request as unauthenticated", e)
                        null
                    }
            validated?.let {
                val authorities = it.scopes.map { scope -> SimpleGrantedAuthority(scope.name) }
                SecurityContextHolder.getContext().authentication =
                    ApiKeyAuthentication(it.tenantId, rawKey.take(12), authorities)
            }
        }
        chain.doFilter(request, response)
    }

    private fun extractRawKey(request: HttpServletRequest): String? {
        request.getHeader("X-API-Key")?.takeIf { it.isNotBlank() }?.let { return it }
        return request
            .getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()
    }
}
