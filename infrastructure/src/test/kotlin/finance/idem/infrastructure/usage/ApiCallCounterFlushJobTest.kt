package finance.idem.infrastructure.usage

import finance.idem.application.usage.UsageMeteringService
import finance.idem.core.TenantId
import finance.idem.core.usage.MetricType
import finance.idem.infrastructure.security.ApiCallCounter
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class ApiCallCounterFlushJobTest {
    @Mock lateinit var apiCallCounter: ApiCallCounter

    @Mock lateinit var usageMeteringService: UsageMeteringService

    private lateinit var job: ApiCallCounterFlushJob

    private val tenantId = TenantId.generate()

    @BeforeEach
    fun setUp() {
        job = ApiCallCounterFlushJob(apiCallCounter, usageMeteringService)
    }

    @Test
    fun `does nothing when the counter has no accumulated calls`() {
        whenever(apiCallCounter.drainAndReset()).thenReturn(emptyMap())

        job.flush()

        verify(usageMeteringService, never()).recordUsage(any(), any(), any(), any())
    }

    @Test
    fun `records API_CALL_COUNT usage for each tenant with a non-zero delta`() {
        whenever(apiCallCounter.drainAndReset()).thenReturn(mapOf(tenantId to 7L))

        job.flush()

        verify(usageMeteringService).recordUsage(tenantId, MetricType.API_CALL_COUNT, 7L)
    }

    @Test
    fun `a failure recording one tenant does not prevent recording the others`() {
        val otherTenant = TenantId.generate()
        whenever(apiCallCounter.drainAndReset()).thenReturn(mapOf(tenantId to 3L, otherTenant to 5L))
        whenever(usageMeteringService.recordUsage(tenantId, MetricType.API_CALL_COUNT, 3L))
            .thenThrow(RuntimeException("db down"))

        job.flush() // must not throw

        verify(usageMeteringService).recordUsage(otherTenant, MetricType.API_CALL_COUNT, 5L)
    }

    @Test
    fun `flush() has no SchedulerLock annotation — every replica must flush its own in-heap counts`() {
        val method = ApiCallCounterFlushJob::class.java.getDeclaredMethod("flush")

        assertNull(
            method.getAnnotation(SchedulerLock::class.java),
            "ApiCallCounterFlushJob.flush() must NOT be @SchedulerLock-guarded — ApiCallCounter's " +
                "state is per-JVM/per-replica in-heap, not shared, so locking this job would silently " +
                "drop every replica's counts but one. See the class KDoc.",
        )
    }

    @Test
    fun `flushOnShutdown() has no SchedulerLock annotation`() {
        val method = ApiCallCounterFlushJob::class.java.getDeclaredMethod("flushOnShutdown")

        assertNull(
            method.getAnnotation(SchedulerLock::class.java),
            "ApiCallCounterFlushJob.flushOnShutdown() must NOT be @SchedulerLock-guarded, for the " +
                "same reason as flush() — see the class KDoc.",
        )
    }

    @Test
    fun `flushOnShutdown delegates to flush`() {
        whenever(apiCallCounter.drainAndReset()).thenReturn(mapOf(tenantId to 7L))

        job.flushOnShutdown()

        verify(usageMeteringService).recordUsage(tenantId, MetricType.API_CALL_COUNT, 7L)
    }
}
