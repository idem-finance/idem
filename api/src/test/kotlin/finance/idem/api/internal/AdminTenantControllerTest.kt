package finance.idem.api.internal

import finance.idem.application.tenant.InvalidAdminToken
import finance.idem.application.tenant.ProvisionTenantUseCase
import finance.idem.application.tenant.ProvisionedTenant
import finance.idem.application.tenant.SuspendTenantUseCase
import finance.idem.application.tenant.TenantNotFound
import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

@WebMvcTest(AdminTenantController::class)
@AutoConfigureMockMvc(addFilters = false)
class AdminTenantControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var provisionTenantUseCase: ProvisionTenantUseCase

    @MockitoBean
    lateinit var suspendTenantUseCase: SuspendTenantUseCase

    @Test
    fun `provision returns 201 with raw key on success`() {
        val tenantId = TenantId.generate()
        whenever(provisionTenantUseCase.execute(any()))
            .thenReturn(Result.success(ProvisionedTenant(tenantId, "sk_live_abc123", "https://cloud.idem.finance/t/${tenantId.value}")))

        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"Acme","contactEmail":"ops@acme.com"}"""
                header("X-Internal-Admin-Token", "correct-token")
            }.andExpect {
                status { isCreated() }
            }
    }

    @Test
    fun `provision returns 401 when admin token is invalid`() {
        whenever(provisionTenantUseCase.execute(any()))
            .thenReturn(Result.failure(InvalidAdminToken("bad token")))

        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"Acme","contactEmail":"ops@acme.com"}"""
            }.andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `provision returns 400 when organizationName is blank`() {
        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"","contactEmail":"ops@acme.com"}"""
                header("X-Internal-Admin-Token", "correct-token")
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `provision returns 400 when contactEmail is not a valid address`() {
        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"Acme","contactEmail":"not-an-email"}"""
                header("X-Internal-Admin-Token", "correct-token")
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `suspend returns 200 with suspendedAt on success`() {
        whenever(suspendTenantUseCase.execute(anyOrNull(), any()))
            .thenReturn(Result.success(Instant.now()))

        mockMvc
            .post("/internal/admin/tenants/${UUID.randomUUID()}/suspend") {
                header("X-Internal-Admin-Token", "correct-token")
            }.andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `suspend returns 404 when tenant does not exist`() {
        whenever(suspendTenantUseCase.execute(anyOrNull(), any()))
            .thenReturn(Result.failure(TenantNotFound("not found")))

        mockMvc
            .post("/internal/admin/tenants/${UUID.randomUUID()}/suspend") {
                header("X-Internal-Admin-Token", "correct-token")
            }.andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `suspend returns 401 when admin token is invalid`() {
        whenever(suspendTenantUseCase.execute(anyOrNull(), any()))
            .thenReturn(Result.failure(InvalidAdminToken("bad token")))

        mockMvc
            .post("/internal/admin/tenants/${UUID.randomUUID()}/suspend") {
            }.andExpect {
                status { isUnauthorized() }
            }
    }
}
