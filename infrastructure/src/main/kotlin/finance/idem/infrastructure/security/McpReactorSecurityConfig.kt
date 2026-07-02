package finance.idem.infrastructure.security

import io.micrometer.context.ContextRegistry
import io.micrometer.context.ThreadLocalAccessor
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import reactor.core.publisher.Hooks

/**
 * Propagates Spring Security's SecurityContextHolder across Reactor thread boundaries.
 *
 * Spring AI's McpAsyncServer dispatches tool execution asynchronously on a Reactor
 * scheduler thread. SecurityContextHolder uses ThreadLocal, so the auth injected by
 * McpSseAuthBridgeFilter on the HTTP request thread is invisible to the Reactor worker.
 *
 * Hooks.enableAutomaticContextPropagation() makes Reactor capture a snapshot of all
 * registered ThreadLocals at subscription time, and restore that snapshot on every
 * operator that switches threads. SecurityContextAccessor registers SecurityContextHolder
 * so @PreAuthorize and tenantId() see the correct auth on whatever thread Spring AI uses.
 */
@Configuration
class McpReactorSecurityConfig {
    @PostConstruct
    fun configureReactorContextPropagation() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(SecurityContextAccessor())
        Hooks.enableAutomaticContextPropagation()
    }
}

private class SecurityContextAccessor : ThreadLocalAccessor<SecurityContext> {
    override fun key(): Any = KEY

    override fun getValue(): SecurityContext = SecurityContextHolder.getContext()

    override fun setValue(value: SecurityContext) = SecurityContextHolder.setContext(value)

    override fun reset() = SecurityContextHolder.clearContext()

    companion object {
        private const val KEY = "spring.security.context"
    }
}
