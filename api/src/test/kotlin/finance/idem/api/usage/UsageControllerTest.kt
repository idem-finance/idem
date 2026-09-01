package finance.idem.api.usage

import finance.idem.api.security.TestSecurityConfig
import finance.idem.application.usage.UsageMeteringService
import finance.idem.core.TenantId
import finance.idem.core.usage.MetricType
import finance.idem.core.usage.UsageSummary
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

@WebMvcTest(UsageController::class)
@Import(TestSecurityConfig::class)
class UsageControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var usageMeteringService: UsageMeteringService

    private val tenantId = TenantId(UUID.randomUUID())

    private fun adminAuth() = TestingAuthenticationToken(tenantId, null, "ADMIN")

    private fun limitedAuth() = TestingAuthenticationToken(tenantId, null, "TRANSACTIONS_WRITE")

    @Test
    fun `current-period returns 200 with usage and limits per metric type`() {
        val periodStart = Instant.parse("2026-08-01T00:00:00Z")
        val periodEnd = Instant.parse("2026-09-01T00:00:00Z")
        whenever(usageMeteringService.getMonthlyUsage(any(), any())).thenReturn(
            UsageSummary(
                tenantId = tenantId,
                periodStart = periodStart,
                periodEnd = periodEnd,
                usage = mapOf(MetricType.TRANSACTION_COUNT to 42L),
                limits = mapOf(MetricType.TRANSACTION_COUNT to 1000L),
            ),
        )

        mockMvc
            .get("/api/v1/usage/current-period") {
                with(authentication(adminAuth()))
            }.andExpect {
                status { isOk() }
                jsonPath("$.periodStart") { value("2026-08-01T00:00:00Z") }
                jsonPath("$.metrics[?(@.metricType=='TRANSACTION_COUNT')].usage") { value(42) }
                jsonPath("$.metrics[?(@.metricType=='TRANSACTION_COUNT')].limit") { value(1000) }
            }
    }

    @Test
    fun `current-period reports a null limit as unlimited`() {
        whenever(usageMeteringService.getMonthlyUsage(any(), any())).thenReturn(
            UsageSummary(
                tenantId = tenantId,
                periodStart = Instant.now(),
                periodEnd = Instant.now(),
                usage = mapOf(MetricType.API_CALL_COUNT to 5L),
                limits = mapOf(MetricType.API_CALL_COUNT to null),
            ),
        )

        mockMvc
            .get("/api/v1/usage/current-period") {
                with(authentication(adminAuth()))
            }.andExpect {
                status { isOk() }
                jsonPath("$.metrics[?(@.metricType=='API_CALL_COUNT')].limit") { value(hasItem(nullValue())) }
            }
    }

    @Test
    fun `current-period queries the current calendar month`() {
        whenever(usageMeteringService.getMonthlyUsage(any(), any())).thenReturn(
            UsageSummary(tenantId, Instant.now(), Instant.now(), emptyMap(), emptyMap()),
        )

        mockMvc.get("/api/v1/usage/current-period") { with(authentication(adminAuth())) }

        verify(usageMeteringService).getMonthlyUsage(tenantId, YearMonth.now())
    }

    @Test
    fun `current-period returns 403 when caller lacks ADMIN scope`() {
        mockMvc
            .get("/api/v1/usage/current-period") {
                with(authentication(limitedAuth()))
            }.andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `current-period returns 401 with no auth`() {
        mockMvc
            .get("/api/v1/usage/current-period")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `current-period returns 401 when the principal is not a TenantId`() {
        // Passes @PreAuthorize (ADMIN authority present) but reaches the controller's own
        // `as? TenantId` fallback — distinct from the no-auth case above, which Spring
        // Security rejects before the controller method ever runs.
        mockMvc
            .get("/api/v1/usage/current-period") {
                with(authentication(TestingAuthenticationToken("not-a-tenant-id", null, "ADMIN")))
            }.andExpect {
                status { isUnauthorized() }
            }
    }
}
