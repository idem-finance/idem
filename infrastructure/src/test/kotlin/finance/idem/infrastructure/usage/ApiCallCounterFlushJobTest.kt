package finance.idem.infrastructure.usage

import finance.idem.application.usage.UsageMeteringService
import finance.idem.core.TenantId
import finance.idem.core.usage.MetricType
import finance.idem.infrastructure.security.ApiCallCounter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

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

        verify(usageMeteringService, never()).recordUsage(any(), any(), any())
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
}
