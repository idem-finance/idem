package finance.idem.infrastructure.persistence.usage

import finance.idem.core.TenantId
import finance.idem.core.usage.MetricType
import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UsageMetricRepositoryAdapter::class)
class UsageMetricRepositoryAdapterIntegrationTest : SharedPostgresTestBase() {
    @Autowired lateinit var adapter: UsageMetricRepositoryAdapter

    private val tenantId = TenantId.generate()

    // @DataJpaTest wraps each test in a rolled-back transaction, but rollupHour/recordEvent
    // run in their own @Transactional (REQUIRED, joins this one) — reads within the same test
    // still see uncommitted writes because Postgres read-your-own-writes applies within one
    // transaction regardless of isolation level.

    @Test
    fun `recordEvent persists a raw row visible to rawSumsBetween`() {
        val now = Instant.now()
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 1L, now)
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 1L, now)

        val sums = adapter.rawSumsBetween(tenantId, now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(1)))

        assertEquals(2L, sums[MetricType.TRANSACTION_COUNT])
    }

    @Test
    fun `rawSumsBetween excludes events outside the requested window`() {
        val now = Instant.now()
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 5L, now.minus(Duration.ofHours(2)))
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 3L, now)
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 7L, now.plus(Duration.ofHours(2)))

        val sums = adapter.rawSumsBetween(tenantId, now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(1)))

        assertEquals(
            3L,
            sums[MetricType.TRANSACTION_COUNT],
            "events before `from` or at/after `to` must not be included — this is the bug #4 fixed (an unbounded upper end)",
        )
    }

    @Test
    fun `rawSumsBetween groups multiple metric types into one call`() {
        val now = Instant.now()
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 5L, now)
        adapter.recordEvent(tenantId, MetricType.API_CALL_COUNT, 9L, now)

        val sums = adapter.rawSumsBetween(tenantId, now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(1)))

        assertEquals(5L, sums[MetricType.TRANSACTION_COUNT])
        assertEquals(9L, sums[MetricType.API_CALL_COUNT])
    }

    @Test
    fun `recordEvent with the same idempotencyKey is a no-op on the second call`() {
        val now = Instant.now()
        adapter.recordEvent(tenantId, MetricType.CHAIN_EVENT_COUNT, 1L, now, idempotencyKey = "EVM_1:0xabc:0")
        adapter.recordEvent(tenantId, MetricType.CHAIN_EVENT_COUNT, 1L, now, idempotencyKey = "EVM_1:0xabc:0")

        val sums = adapter.rawSumsBetween(tenantId, now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(1)))

        assertEquals(
            1L,
            sums[MetricType.CHAIN_EVENT_COUNT],
            "a redelivered event with the same idempotency key must not be double-counted",
        )
    }

    @Test
    fun `recordEvent with different idempotencyKeys records both`() {
        val now = Instant.now()
        adapter.recordEvent(tenantId, MetricType.CHAIN_EVENT_COUNT, 1L, now, idempotencyKey = "EVM_1:0xabc:0")
        adapter.recordEvent(tenantId, MetricType.CHAIN_EVENT_COUNT, 1L, now, idempotencyKey = "EVM_1:0xdef:0")

        val sums = adapter.rawSumsBetween(tenantId, now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(1)))

        assertEquals(2L, sums[MetricType.CHAIN_EVENT_COUNT])
    }

    @Test
    fun `rawSumsBetween is scoped to the requested tenant`() {
        val now = Instant.now()
        val otherTenant = TenantId.generate()
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 5L, now)
        adapter.recordEvent(otherTenant, MetricType.TRANSACTION_COUNT, 100L, now)

        val sums = adapter.rawSumsBetween(tenantId, now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(1)))

        assertEquals(5L, sums[MetricType.TRANSACTION_COUNT])
    }

    @Test
    fun `rollupHour aggregates raw events into a bucket and is idempotent on a second call`() {
        val hourStart = Instant.now().minus(Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val hourEnd = hourStart.plus(Duration.ofHours(1))
        adapter.recordEvent(tenantId, MetricType.ENTRY_COUNT, 3L, hourStart.plusSeconds(60))
        adapter.recordEvent(tenantId, MetricType.ENTRY_COUNT, 4L, hourStart.plusSeconds(120))

        val firstRun = adapter.rollupHour(hourStart, hourEnd)
        val secondRun = adapter.rollupHour(hourStart, hourEnd) // must not double-count

        val sums = adapter.hourlyBucketSums(tenantId, hourStart, hourEnd.plusSeconds(1))

        assertEquals(1, firstRun)
        assertEquals(0, secondRun, "re-running an already-rolled-up hour must be a no-op (ON CONFLICT DO NOTHING)")
        assertEquals(7L, sums[MetricType.ENTRY_COUNT])
    }

    @Test
    fun `rollupHour aggregates across tenants and metric types independently`() {
        val hourStart = Instant.now().minus(Duration.ofHours(4)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val hourEnd = hourStart.plus(Duration.ofHours(1))
        val otherTenant = TenantId.generate()
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 2L, hourStart.plusSeconds(30))
        adapter.recordEvent(otherTenant, MetricType.TRANSACTION_COUNT, 10L, hourStart.plusSeconds(30))

        adapter.rollupHour(hourStart, hourEnd)

        val tenantSums = adapter.hourlyBucketSums(tenantId, hourStart, hourEnd.plusSeconds(1))
        val otherSums = adapter.hourlyBucketSums(otherTenant, hourStart, hourEnd.plusSeconds(1))

        assertEquals(2L, tenantSums[MetricType.TRANSACTION_COUNT])
        assertEquals(10L, otherSums[MetricType.TRANSACTION_COUNT])
    }

    @Test
    fun `hourlyBucketSums groups multiple metric types into one call`() {
        val hourStart = Instant.now().minus(Duration.ofHours(5)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val hourEnd = hourStart.plus(Duration.ofHours(1))
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 2L, hourStart.plusSeconds(30))
        adapter.recordEvent(tenantId, MetricType.API_CALL_COUNT, 6L, hourStart.plusSeconds(30))

        adapter.rollupHour(hourStart, hourEnd)

        val sums = adapter.hourlyBucketSums(tenantId, hourStart, hourEnd.plusSeconds(1))

        assertEquals(2L, sums[MetricType.TRANSACTION_COUNT])
        assertEquals(6L, sums[MetricType.API_CALL_COUNT])
    }

    @Test
    fun `watermark defaults to the V29 seed value and advances on request`() {
        val initial = adapter.currentWatermark()
        assertTrue(initial <= Instant.now(), "seed watermark should be at or before now")

        val newWatermark = initial.plus(Duration.ofHours(1))
        adapter.advanceWatermark(newWatermark)

        assertEquals(newWatermark, adapter.currentWatermark())
    }
}
