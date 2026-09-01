package finance.idem.infrastructure.usage

import finance.idem.core.usage.UsageMetricRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class UsageMetricsRollupJobTest {
    @Mock lateinit var usageMetricRepository: UsageMetricRepository

    private lateinit var job: UsageMetricsRollupJob

    private val safetyBufferMinutes = 5L

    @BeforeEach
    fun setUp() {
        job = UsageMetricsRollupJob(usageMetricRepository, safetyBufferMinutes)
    }

    @Test
    fun `rolls up no hours when the watermark is inside the safety buffer`() {
        val now = Instant.now()
        whenever(usageMetricRepository.currentWatermark()).thenReturn(now.minus(Duration.ofMinutes(2)))

        job.rollup()

        verify(usageMetricRepository, never()).rollupHour(any(), any())
        verify(usageMetricRepository, never()).advanceWatermark(any())
    }

    @Test
    fun `rolls up exactly the hours that are fully elapsed beyond the safety buffer`() {
        // Not hour-truncated on purpose: hourEnd values are computed relative to "now" (10m and
        // 1h10m before it), so eligibility doesn't depend on where in the current hour the test
        // happens to run.
        val now = Instant.now()
        val watermark = now.minus(Duration.ofHours(2)).minus(Duration.ofMinutes(10))
        whenever(usageMetricRepository.currentWatermark()).thenReturn(watermark)

        job.rollup()

        // Two full hours are eligible: [watermark, watermark+1h) (hourEnd = now - 1h10m) and
        // [watermark+1h, watermark+2h) (hourEnd = now - 10m) — both safely before the 5-minute
        // safety-buffer cutoff. A third hour would end at now + 50m, not yet elapsed.
        verify(usageMetricRepository, times(2)).rollupHour(any(), any())
        verify(usageMetricRepository).advanceWatermark(watermark.plus(Duration.ofHours(1)))
        verify(usageMetricRepository).advanceWatermark(watermark.plus(Duration.ofHours(2)))
    }

    @Test
    fun `stops processing and does not advance further hours when rollupHour throws`() {
        val now = Instant.now()
        val watermark = now.minus(Duration.ofHours(2)).minus(Duration.ofMinutes(10))
        whenever(usageMetricRepository.currentWatermark()).thenReturn(watermark)
        whenever(usageMetricRepository.rollupHour(any(), any())).thenThrow(RuntimeException("db down"))

        job.rollup() // must not throw — swallowed and logged

        verify(usageMetricRepository, never()).advanceWatermark(any())
    }
}
