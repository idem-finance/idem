package finance.idem.api.internal

import finance.idem.application.port.AdminTokenAuthenticator
import finance.idem.application.port.AdminTokenLockoutGuard
import finance.idem.application.tenant.InvalidAdminToken
import finance.idem.application.tenant.ProvisionTenantUseCase
import finance.idem.application.tenant.ProvisionedTenant
import finance.idem.application.tenant.ProvisioningInProgress
import finance.idem.application.tenant.SuspendTenantUseCase
import finance.idem.application.tenant.TenantNotFound
import finance.idem.core.TenantId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
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

    @MockitoBean
    lateinit var adminTokenAuthenticator: AdminTokenAuthenticator

    @MockitoBean
    lateinit var lockoutService: AdminTokenLockoutGuard

    @BeforeEach
    fun setup() {
        whenever(lockoutService.isLockedOut(any())).thenReturn(false)
    }

    @Test
    fun `provision returns 201 with raw key on success`() {
        whenever(adminTokenAuthenticator.isValid("correct-token")).thenReturn(true)
        val tenantId = TenantId.generate()
        whenever(provisionTenantUseCase.execute(any()))
            .thenReturn(Result.success(ProvisionedTenant(tenantId, "sk_live_abc123", "https://cloud.idem.finance/t/${tenantId.value}")))

        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"Acme","contactEmail":"ops@acme.com"}"""
                header("X-Internal-Admin-Token", "correct-token")
                header("Idempotency-Key", "idem-key-1")
            }.andExpect {
                status { isCreated() }
            }
    }

    @Test
    fun `provision returns 401 when admin token is invalid, without ever calling the use case`() {
        whenever(adminTokenAuthenticator.isValid(any())).thenReturn(false)

        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"Acme","contactEmail":"ops@acme.com"}"""
                header("Idempotency-Key", "idem-key-1")
            }.andExpect {
                status { isUnauthorized() }
            }
        verify(provisionTenantUseCase, never()).execute(any())
        verify(lockoutService).recordFailure(any())
    }

    @Test
    fun `provision returns 401 for an invalid token even when the body is malformed too — auth is checked first`() {
        whenever(adminTokenAuthenticator.isValid(any())).thenReturn(false)

        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"","contactEmail":"not-an-email"}"""
            }.andExpect {
                status { isUnauthorized() }
            }
        verify(provisionTenantUseCase, never()).execute(any())
    }

    @Test
    fun `provision returns 429 when the caller is locked out, without checking the token`() {
        whenever(lockoutService.isLockedOut(any())).thenReturn(true)

        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"Acme","contactEmail":"ops@acme.com"}"""
                header("Idempotency-Key", "idem-key-1")
            }.andExpect {
                status { isEqualTo(429) }
            }
        verify(adminTokenAuthenticator, never()).isValid(any())
        verify(provisionTenantUseCase, never()).execute(any())
    }

    @Test
    fun `provision returns 400 when Idempotency-Key header is missing`() {
        whenever(adminTokenAuthenticator.isValid("correct-token")).thenReturn(true)

        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"Acme","contactEmail":"ops@acme.com"}"""
                header("X-Internal-Admin-Token", "correct-token")
            }.andExpect {
                status { isBadRequest() }
            }
        verify(provisionTenantUseCase, never()).execute(any())
    }

    @Test
    fun `provision returns 400 when organizationName is blank`() {
        whenever(adminTokenAuthenticator.isValid("correct-token")).thenReturn(true)

        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"","contactEmail":"ops@acme.com"}"""
                header("X-Internal-Admin-Token", "correct-token")
                header("Idempotency-Key", "idem-key-1")
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `provision returns 400 when contactEmail is not a valid address`() {
        whenever(adminTokenAuthenticator.isValid("correct-token")).thenReturn(true)

        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"Acme","contactEmail":"not-an-email"}"""
                header("X-Internal-Admin-Token", "correct-token")
                header("Idempotency-Key", "idem-key-1")
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `provision returns 409 when the same Idempotency-Key is already being processed`() {
        whenever(adminTokenAuthenticator.isValid("correct-token")).thenReturn(true)
        whenever(provisionTenantUseCase.execute(any()))
            .thenReturn(Result.failure(ProvisioningInProgress("in progress")))

        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"Acme","contactEmail":"ops@acme.com"}"""
                header("X-Internal-Admin-Token", "correct-token")
                header("Idempotency-Key", "idem-key-1")
            }.andExpect {
                status { isEqualTo(409) }
            }
    }

    @Test
    fun `provision returns 500 and logs when the use case fails unexpectedly`() {
        whenever(adminTokenAuthenticator.isValid("correct-token")).thenReturn(true)
        whenever(provisionTenantUseCase.execute(any()))
            .thenReturn(Result.failure(RuntimeException("boom")))

        mockMvc
            .post("/internal/admin/tenants") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"organizationName":"Acme","contactEmail":"ops@acme.com"}"""
                header("X-Internal-Admin-Token", "correct-token")
                header("Idempotency-Key", "idem-key-1")
            }.andExpect {
                status { isEqualTo(500) }
            }
    }

    @Test
    fun `suspend returns 200 with suspendedAt on success`() {
        whenever(adminTokenAuthenticator.isValid("correct-token")).thenReturn(true)
        whenever(suspendTenantUseCase.execute(any(), any()))
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
        whenever(adminTokenAuthenticator.isValid("correct-token")).thenReturn(true)
        whenever(suspendTenantUseCase.execute(any(), any()))
            .thenReturn(Result.failure(TenantNotFound("not found")))

        mockMvc
            .post("/internal/admin/tenants/${UUID.randomUUID()}/suspend") {
                header("X-Internal-Admin-Token", "correct-token")
            }.andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `suspend returns 401 when admin token is invalid, without ever calling the use case`() {
        whenever(adminTokenAuthenticator.isValid(any())).thenReturn(false)

        mockMvc
            .post("/internal/admin/tenants/${UUID.randomUUID()}/suspend") {
            }.andExpect {
                status { isUnauthorized() }
            }
        verify(suspendTenantUseCase, never()).execute(any(), any())
    }

    @Test
    fun `suspend returns 429 when the caller is locked out`() {
        whenever(lockoutService.isLockedOut(any())).thenReturn(true)

        mockMvc
            .post("/internal/admin/tenants/${UUID.randomUUID()}/suspend") {
            }.andExpect {
                status { isEqualTo(429) }
            }
        verify(suspendTenantUseCase, never()).execute(any(), any())
    }

    @Test
    fun `InvalidAdminToken raised from the use case itself still maps to 401`() {
        // Defense-in-depth path: the controller's own gate passed, but the use case
        // re-validates and rejects — must still surface as 401, not 500.
        whenever(adminTokenAuthenticator.isValid("correct-token")).thenReturn(true)
        whenever(suspendTenantUseCase.execute(any(), any()))
            .thenReturn(Result.failure(InvalidAdminToken("stale token")))

        mockMvc
            .post("/internal/admin/tenants/${UUID.randomUUID()}/suspend") {
                header("X-Internal-Admin-Token", "correct-token")
            }.andExpect {
                status { isUnauthorized() }
            }
    }
}
