package finance.idem.infrastructure.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Bridges stateless API-key auth with the MCP SSE session lifecycle.
 *
 * Problem: mcp-remote sends X-API-Key only on GET /sse, not on POST /mcp/messages.
 * The Spring AI SSE transport assigns a session UUID after the GET /sse handler runs,
 * so we capture it by intercepting the first SSE write (the "endpoint" event that
 * carries the session ID) and store sessionId → Authentication. Subsequent POSTs to
 * /mcp/messages?sessionId=<uuid> then look up that auth and inject it into the
 * SecurityContext before Spring Security's authorization filter runs.
 */
class McpSseAuthBridgeFilter(
    private val sessionAuthStore: McpSseSessionAuthStore,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        when {
            request.method == "GET" && request.requestURI == "/sse" -> {
                val auth = currentAuth()
                if (auth != null) {
                    chain.doFilter(request, SessionCapturingResponseWrapper(response) { sessionId ->
                        sessionAuthStore.register(sessionId, auth)
                    })
                } else {
                    chain.doFilter(request, response)
                }
            }

            request.requestURI.contains("/mcp/messages") && currentAuth() == null -> {
                request.getParameter("sessionId")?.let { sessionId ->
                    sessionAuthStore.getAuth(sessionId)?.let { storedAuth ->
                        SecurityContextHolder.getContext().authentication = storedAuth
                    }
                }
                chain.doFilter(request, response)
            }

            else -> chain.doFilter(request, response)
        }
    }

    private fun currentAuth(): Authentication? =
        SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
}

// ── Response wrapper — intercepts the first SSE write to extract the session ID ──

private class SessionCapturingResponseWrapper(
    response: HttpServletResponse,
    private val onSessionId: (String) -> Unit,
) : HttpServletResponseWrapper(response) {

    private var capturingStream: SessionCapturingOutputStream? = null

    override fun getOutputStream(): ServletOutputStream {
        if (capturingStream == null) {
            capturingStream = SessionCapturingOutputStream(super.getOutputStream(), onSessionId)
        }
        return capturingStream!!
    }
}

private val SESSION_ID_PATTERN = Regex("""sessionId=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""")

private class SessionCapturingOutputStream(
    private val delegate: ServletOutputStream,
    private val onSessionId: (String) -> Unit,
) : ServletOutputStream() {

    private val buffer = StringBuilder()
    private var captured = false

    override fun write(b: Int) = delegate.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (!captured) {
            buffer.append(String(b, off, len, Charsets.UTF_8))
            SESSION_ID_PATTERN.find(buffer)?.groupValues?.get(1)?.let { sessionId ->
                onSessionId(sessionId)
                captured = true
            }
        }
        delegate.write(b, off, len)
    }

    override fun isReady(): Boolean = delegate.isReady
    override fun setWriteListener(writeListener: WriteListener?) = delegate.setWriteListener(writeListener)
}
