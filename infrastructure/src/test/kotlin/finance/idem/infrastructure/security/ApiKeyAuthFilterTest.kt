package finance.idem.infrastructure.security

import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope
import finance.idem.core.security.ValidatedApiKey
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class ApiKeyAuthFilterTest {

    @Mock lateinit var apiKeyService: ApiKeyService
    @Mock lateinit var request: HttpServletRequest
    @Mock lateinit var response: HttpServletResponse
    @Mock lateinit var chain: FilterChain

    private lateinit var filter: ApiKeyAuthFilter

    private val tenantId = TenantId(UUID.randomUUID())
    private val validatedKey = ValidatedApiKey(tenantId, setOf(ApiScope.TRANSACTIONS_WRITE))

    @BeforeEach
    fun setUp() {
        filter = ApiKeyAuthFilter(apiKeyService)
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `X-API-Key header — valid key sets ApiKeyAuthentication in SecurityContext`() {
        whenever(request.getHeader("X-API-Key")).thenReturn("sk_live_abc123")
        whenever(apiKeyService.validate("sk_live_abc123")).thenReturn(validatedKey)

        filter.doFilter(request, response, chain)

        val auth = SecurityContextHolder.getContext().authentication as ApiKeyAuthentication
        assertEquals(tenantId, auth.tenantId)
        assertEquals(listOf("TRANSACTIONS_WRITE"), auth.authorities.map { it.authority })
    }

    @Test
    fun `Authorization Bearer header — valid key sets ApiKeyAuthentication`() {
        whenever(request.getHeader("X-API-Key")).thenReturn(null)
        whenever(request.getHeader("Authorization")).thenReturn("Bearer sk_live_abc123")
        whenever(apiKeyService.validate("sk_live_abc123")).thenReturn(validatedKey)

        filter.doFilter(request, response, chain)

        val auth = SecurityContextHolder.getContext().authentication as ApiKeyAuthentication
        assertEquals(tenantId, auth.tenantId)
    }

    @Test
    fun `X-API-Key takes precedence over Authorization Bearer when both present`() {
        whenever(request.getHeader("X-API-Key")).thenReturn("sk_live_primary")
        whenever(apiKeyService.validate("sk_live_primary")).thenReturn(validatedKey)

        filter.doFilter(request, response, chain)

        val auth = SecurityContextHolder.getContext().authentication
        assertNotNull(auth)
    }

    @Test
    fun `invalid key — no authentication set, chain continues`() {
        whenever(request.getHeader("X-API-Key")).thenReturn("sk_live_invalid")
        whenever(apiKeyService.validate("sk_live_invalid")).thenReturn(null)

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `missing key headers — no authentication set, chain continues`() {
        whenever(request.getHeader("X-API-Key")).thenReturn(null)
        whenever(request.getHeader("Authorization")).thenReturn(null)

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `valid key — chain always continues after authentication`() {
        whenever(request.getHeader("X-API-Key")).thenReturn("sk_live_abc123")
        whenever(apiKeyService.validate("sk_live_abc123")).thenReturn(validatedKey)

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    @Test
    fun `blank X-API-Key header treated as missing`() {
        whenever(request.getHeader("X-API-Key")).thenReturn("   ")
        whenever(request.getHeader("Authorization")).thenReturn(null)

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(chain).doFilter(request, response)
    }
}
