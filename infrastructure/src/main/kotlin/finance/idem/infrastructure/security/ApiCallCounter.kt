package finance.idem.infrastructure.security

import finance.idem.core.TenantId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-replica, in-memory tally of authenticated API calls per tenant, incremented on the
 * request hot path with no I/O. Flushed periodically by [ApiCallCounterFlushJob].
 *
 * Lossy only on a hard crash (SIGKILL/OOM-kill, no grace period) by design: counts accumulated
 * since the last flush (at most one flush interval, ~1 minute at the default) are lost if this
 * replica dies before flushing. A graceful shutdown (e.g. GKE rolling deploy/scale-down) does
 * NOT lose counts — [ApiCallCounterFlushJob.flushOnShutdown] flushes synchronously via
 * `@PreDestroy` before the process exits. This is a billing-*visibility* feature, not part of
 * the append-only ledger — `journal_lines` and `transactions` durability is completely
 * unaffected by a lost API-call count.
 *
 * [increment] and [drainAndReset] both go through [ConcurrentHashMap]'s per-key `compute`
 * family, which locks the map's bin for the key for the duration of the remapping function —
 * this serializes a given tenant's increments against a concurrent drain of that same tenant,
 * so no increment can land on a value a drain has already removed. A `LongAdder`-per-tenant
 * scheme (the previous implementation) could not offer this: `computeIfAbsent(...).increment()`
 * is two separate map operations, so a drain could remove the adder between them and silently
 * lose the increment. This trades LongAdder's striped-cell throughput for a per-bin lock, which
 * is the right call here since increments happen once per authenticated request per tenant, not
 * in a hot inner loop — correctness matters more than throughput for a billing input.
 */
@Component
class ApiCallCounter {
    private val counts = ConcurrentHashMap<TenantId, Long>()

    fun increment(tenantId: TenantId) {
        counts.compute(tenantId) { _, existing -> (existing ?: 0L) + 1 }
    }

    /** Atomically snapshots and resets all counters, returning only tenants with a non-zero delta. */
    fun drainAndReset(): Map<TenantId, Long> {
        val snapshot = mutableMapOf<TenantId, Long>()
        for (tenantId in counts.keys.toList()) {
            var drained: Long? = null
            counts.computeIfPresent(tenantId) { _, value ->
                drained = value
                null
            }
            drained?.let { if (it > 0) snapshot[tenantId] = it }
        }
        return snapshot
    }
}
