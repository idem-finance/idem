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
            apiKeyService.validate(rawKey)?.let { validated ->
                val authorities = validated.scopes.map { SimpleGrantedAuthority(it.name) }
                SecurityContextHolder.getContext().authentication =
                    ApiKeyAuthentication(validated.tenantId, authorities)
            }
        }
        chain.doFilter(request, response)
    }

    private fun extractRawKey(request: HttpServletRequest): String? {
        request.getHeader("X-API-Key")?.takeIf { it.isNotBlank() }?.let { return it }
        return request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()
    }
}
