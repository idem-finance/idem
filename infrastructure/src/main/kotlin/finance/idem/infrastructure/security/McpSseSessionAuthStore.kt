package finance.idem.infrastructure.security

import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges the MCP SSE session model with stateless API-key auth.
 *
 * mcp-remote (and most MCP clients) send the API key header only on the initial GET /sse
 * request, not on subsequent POST /mcp/messages requests. This store maps the Spring AI
 * session ID (generated when the SSE connection opens) to the Authentication established
 * at that time, so tool-call POSTs can be authenticated without a per-request header.
 */
@Component
class McpSseSessionAuthStore {
    private val store = ConcurrentHashMap<String, Authentication>()

    fun register(
        sessionId: String,
        auth: Authentication,
    ) {
        store[sessionId] = auth
    }

    fun getAuth(sessionId: String): Authentication? = store[sessionId]

    fun remove(sessionId: String) {
        store.remove(sessionId)
    }
}
