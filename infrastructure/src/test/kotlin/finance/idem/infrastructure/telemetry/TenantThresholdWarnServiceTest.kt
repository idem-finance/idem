package finance.idem.infrastructure.telemetry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import finance.idem.application.telemetry.TelemetryStatsPort
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TenantThresholdWarnServiceTest {

    private val telemetryStatsPort: TelemetryStatsPort = mock()

    private lateinit var logAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun attachLogCapture() {
        logger = LoggerFactory.getLogger(TenantThresholdWarnService::class.java) as Logger
        logAppender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logAppender)
    }

    @AfterEach
    fun detachLogCapture() {
        logger.detachAppender(logAppender)
    }

    private fun service(threshold: Long = 10) =
        TenantThresholdWarnService(telemetryStatsPort, threshold)

    @Test
    fun `no log when count is below threshold`() {
        whenever(telemetryStatsPort.tenantCount()).thenReturn(5L)

        service(threshold = 10).checkTenantThreshold()

        assertEquals(0, logAppender.list.size)
    }

    @Test
    fun `no log when count equals threshold`() {
        whenever(telemetryStatsPort.tenantCount()).thenReturn(10L)

        service(threshold = 10).checkTenantThreshold()

        assertEquals(0, logAppender.list.size)
    }

    @Test
    fun `logs INFO when count exceeds threshold`() {
        whenever(telemetryStatsPort.tenantCount()).thenReturn(11L)

        service(threshold = 10).checkTenantThreshold()

        val infos = logAppender.list.filter { it.level == Level.INFO }
        assertEquals(1, infos.size)
        assertTrue(infos[0].formattedMessage.contains("11"))
        assertTrue(infos[0].formattedMessage.contains("idem.finance"))
    }

    @Test
    fun `does not call tenantCount when threshold is -1`() {
        service(threshold = -1).checkTenantThreshold()

        verify(telemetryStatsPort, never()).tenantCount()
        assertEquals(0, logAppender.list.size)
    }

    @Test
    fun `does not propagate when tenantCount throws`() {
        whenever(telemetryStatsPort.tenantCount()).thenThrow(RuntimeException("db down"))

        service(threshold = 10).checkTenantThreshold()

        val warns = logAppender.list.filter { it.level == Level.WARN }
        assertEquals(1, warns.size)
    }
}
