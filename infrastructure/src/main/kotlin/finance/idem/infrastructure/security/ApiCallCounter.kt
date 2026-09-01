package finance.idem.infrastructure.security

import finance.idem.core.TenantId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Per-replica, in-memory tally of authenticated API calls per tenant, incremented on the
 * request hot path with no I/O. Flushed periodically by [ApiCallCounterFlushJob].
 *
 * Lossy on crash by design: counts accumulated since the last flush (at most one flush
 * interval, ~1 minute at the default) are lost if this replica dies before flushing. This is
 * a billing-*visibility* feature, not part of the append-only ledger — `journal_lines` and
 * `transactions` durability is completely unaffected by a lost API-call count.
 */
@Component
class ApiCallCounter {
    private val counts = ConcurrentHashMap<TenantId, LongAdder>()

    fun increment(tenantId: TenantId) {
        counts.computeIfAbsent(tenantId) { LongAdder() }.increment()
    }

    /** Atomically snapshots and resets all counters, returning only tenants with a non-zero delta. */
    fun drainAndReset(): Map<TenantId, Long> {
        val snapshot = mutableMapOf<TenantId, Long>()
        val tenants = counts.keys.toList()
        for (tenantId in tenants) {
            val adder = counts.remove(tenantId) ?: continue
            val value = adder.sum()
            if (value > 0) snapshot[tenantId] = value
        }
        return snapshot
    }
}
