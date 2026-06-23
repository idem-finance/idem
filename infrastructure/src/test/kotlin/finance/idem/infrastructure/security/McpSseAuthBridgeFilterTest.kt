package finance.idem.infrastructure.security

import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class McpSseAuthBridgeFilterTest {

    @Mock lateinit var chain: FilterChain

    private val store = McpSseSessionAuthStore()
    private val filter = McpSseAuthBridgeFilter(store)

    private val tenantId = TenantId(UUID.randomUUID())
    private val auth = ApiKeyAuthentication(tenantId, "sk_live_test", listOf(SimpleGrantedAuthority(ApiScope.AGENTS_EXECUTE.name)))

    @BeforeEach fun setUp() = SecurityContextHolder.clearContext()
    @AfterEach fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `POST mcp messages — injects stored session auth into SecurityContext`() {
        val sessionId = UUID.randomUUID().toString()
        store.register(sessionId, auth)

        val request = MockHttpServletRequest("POST", "/mcp/messages")
        request.addParameter("sessionId", sessionId)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        val injected = SecurityContextHolder.getContext().authentication
        assertNotNull(injected)
        assertEquals(tenantId, injected.principal)
        verify(chain).doFilter(any(), any())
    }

    @Test
    fun `POST mcp messages — no stored session — SecurityContext stays empty`() {
        val request = MockHttpServletRequest("POST", "/mcp/messages")
        request.addParameter("sessionId", UUID.randomUUID().toString())
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(chain).doFilter(any(), any())
    }

    @Test
    fun `POST mcp messages — already authenticated — does not overwrite SecurityContext`() {
        val otherAuth = ApiKeyAuthentication(TenantId(UUID.randomUUID()), "sk_live_other", emptyList())
        SecurityContextHolder.getContext().authentication = otherAuth

        val sessionId = UUID.randomUUID().toString()
        store.register(sessionId, auth)

        val request = MockHttpServletRequest("POST", "/mcp/messages")
        request.addParameter("sessionId", sessionId)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertEquals(otherAuth, SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `GET sse — with auth — captures session ID from SSE endpoint event and registers in store`() {
        SecurityContextHolder.getContext().authentication = auth

        val sessionId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val sseData = "event: endpoint\ndata: /mcp/messages?sessionId=$sessionId\n\n"

        val request = MockHttpServletRequest("GET", "/sse")
        val response = MockHttpServletResponse()

        val writingChain = FilterChain { _, resp ->
            resp.outputStream.write(sseData.toByteArray(Charsets.UTF_8))
        }

        filter.doFilter(request, response, writingChain)

        assertEquals(auth, store.getAuth(sessionId))
    }

    @Test
    fun `GET sse — with auth — second call to getOutputStream returns same wrapper instance`() {
        SecurityContextHolder.getContext().authentication = auth

        val sessionId = "b2c3d4e5-f6a7-8901-bcde-f12345678901"
        val sseData = "event: endpoint\ndata: /mcp/messages?sessionId=$sessionId\n\n"

        val request = MockHttpServletRequest("GET", "/sse")
        val response = MockHttpServletResponse()

        val writingChain = FilterChain { _, resp ->
            // Call getOutputStream() twice — should get the same wrapper
            resp.outputStream.write(sseData.toByteArray(Charsets.UTF_8))
            resp.outputStream.write("ping\n".toByteArray(Charsets.UTF_8))
        }

        filter.doFilter(request, response, writingChain)

        assertEquals(auth, store.getAuth(sessionId))
    }

    @Test
    fun `GET sse — no auth in context — passes through without wrapping`() {
        val request = MockHttpServletRequest("GET", "/sse")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(any(), any())
    }

    @Test
    fun `McpSseSessionAuthStore — register and retrieve`() {
        val sessionId = UUID.randomUUID().toString()
        store.register(sessionId, auth)
        assertEquals(auth, store.getAuth(sessionId))
    }

    @Test
    fun `McpSseSessionAuthStore — remove clears entry`() {
        val sessionId = UUID.randomUUID().toString()
        store.register(sessionId, auth)
        store.remove(sessionId)
        assertNull(store.getAuth(sessionId))
    }

    @Test
    fun `McpSseSessionAuthStore — unknown session returns null`() {
        assertNull(store.getAuth(UUID.randomUUID().toString()))
    }
}
