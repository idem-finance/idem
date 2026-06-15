package finance.idem.infrastructure.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.MDC
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class TraceIdFilterTest {

    @Mock lateinit var request: HttpServletRequest
    @Mock lateinit var response: HttpServletResponse
    @Mock lateinit var chain: FilterChain

    private val filter = TraceIdFilter()

    @AfterEach
    fun tearDown() {
        MDC.clear()
    }

    @Test
    fun `sets X-Idem-Trace-Id response header to a valid UUID`() {
        filter.doFilter(request, response, chain)

        val captor = argumentCaptor<String>()
        verify(response).setHeader(eq(TraceIdFilter.TRACE_ID_HEADER), captor.capture())
        UUID.fromString(captor.firstValue)
    }

    @Test
    fun `populates MDC traceId during chain doFilter and clears it after`() {
        var mdcDuringChain: String? = null
        whenever(chain.doFilter(request, response)).then {
            mdcDuringChain = MDC.get(TraceIdFilter.MDC_KEY)
            null
        }

        filter.doFilter(request, response, chain)

        assertNotNull(mdcDuringChain)
        UUID.fromString(mdcDuringChain)
        assertNull(MDC.get(TraceIdFilter.MDC_KEY))
    }

    @Test
    fun `MDC is cleared even when chain throws`() {
        whenever(chain.doFilter(request, response)).thenThrow(RuntimeException("boom"))

        assertTrue(runCatching { filter.doFilter(request, response, chain) }.isFailure)
        assertNull(MDC.get(TraceIdFilter.MDC_KEY))
    }

    @Test
    fun `header value matches MDC value for the same request`() {
        var mdcDuringChain: String? = null
        whenever(chain.doFilter(request, response)).then {
            mdcDuringChain = MDC.get(TraceIdFilter.MDC_KEY)
            null
        }

        val captor = argumentCaptor<String>()
        filter.doFilter(request, response, chain)
        verify(response).setHeader(eq(TraceIdFilter.TRACE_ID_HEADER), captor.capture())

        assertEquals(captor.firstValue, mdcDuringChain)
    }
}
