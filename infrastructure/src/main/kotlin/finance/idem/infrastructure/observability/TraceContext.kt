package finance.idem.infrastructure.observability

import org.slf4j.MDC
import java.util.UUID

/**
 * Resolves a correlation ID for a `domain_events` write: the request's trace ID when one is
 * bound (see [TraceIdFilter]), or a fresh UUID for writes with no live HTTP request (a
 * `@Scheduled` sweep, a background chain-reader post). Since MDC is thread-local and a
 * request's whole synchronous call chain runs on one thread, every event written during one
 * request — including nested calls across multiple workflow steps — naturally shares one
 * correlation ID with no explicit threading required.
 */
object TraceContext {
    fun currentOrNew(): String = MDC.get(TraceIdFilter.MDC_KEY) ?: UUID.randomUUID().toString()
}
