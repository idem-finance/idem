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
    fun `recordEvent persists a raw row visible to sumRawSince`() {
        val now = Instant.now()
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 1L, now)
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 1L, now)

        val sum = adapter.sumRawSince(tenantId, MetricType.TRANSACTION_COUNT, now.minus(Duration.ofMinutes(1)))

        assertEquals(2L, sum)
    }

    @Test
    fun `sumRawSince is scoped to the requested metricType and tenant`() {
        val now = Instant.now()
        val otherTenant = TenantId.generate()
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 5L, now)
        adapter.recordEvent(tenantId, MetricType.API_CALL_COUNT, 9L, now)
        adapter.recordEvent(otherTenant, MetricType.TRANSACTION_COUNT, 100L, now)

        assertEquals(5L, adapter.sumRawSince(tenantId, MetricType.TRANSACTION_COUNT, now.minus(Duration.ofMinutes(1))))
    }

    @Test
    fun `rollupHour aggregates raw events into a bucket and is idempotent on a second call`() {
        val hourStart = Instant.now().minus(Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val hourEnd = hourStart.plus(Duration.ofHours(1))
        adapter.recordEvent(tenantId, MetricType.ENTRY_COUNT, 3L, hourStart.plusSeconds(60))
        adapter.recordEvent(tenantId, MetricType.ENTRY_COUNT, 4L, hourStart.plusSeconds(120))

        val firstRun = adapter.rollupHour(hourStart, hourEnd)
        val secondRun = adapter.rollupHour(hourStart, hourEnd) // must not double-count

        val buckets = adapter.findHourlyBuckets(tenantId, MetricType.ENTRY_COUNT, hourStart, hourEnd.plusSeconds(1))

        assertEquals(1, firstRun)
        assertEquals(0, secondRun, "re-running an already-rolled-up hour must be a no-op (ON CONFLICT DO NOTHING)")
        assertEquals(1, buckets.size)
        assertEquals(7L, buckets.single().value)
    }

    @Test
    fun `rollupHour aggregates across tenants and metric types independently`() {
        val hourStart = Instant.now().minus(Duration.ofHours(4)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val hourEnd = hourStart.plus(Duration.ofHours(1))
        val otherTenant = TenantId.generate()
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 2L, hourStart.plusSeconds(30))
        adapter.recordEvent(otherTenant, MetricType.TRANSACTION_COUNT, 10L, hourStart.plusSeconds(30))

        adapter.rollupHour(hourStart, hourEnd)

        val tenantBuckets = adapter.findHourlyBuckets(tenantId, MetricType.TRANSACTION_COUNT, hourStart, hourEnd.plusSeconds(1))
        val otherBuckets = adapter.findHourlyBuckets(otherTenant, MetricType.TRANSACTION_COUNT, hourStart, hourEnd.plusSeconds(1))

        assertEquals(2L, tenantBuckets.single().value)
        assertEquals(10L, otherBuckets.single().value)
    }

    @Test
    fun `watermark defaults to the V30 seed value and advances on request`() {
        val initial = adapter.currentWatermark()
        assertTrue(initial <= Instant.now(), "seed watermark should be at or before now")

        val newWatermark = initial.plus(Duration.ofHours(1))
        adapter.advanceWatermark(newWatermark)

        assertEquals(newWatermark, adapter.currentWatermark())
    }
}
