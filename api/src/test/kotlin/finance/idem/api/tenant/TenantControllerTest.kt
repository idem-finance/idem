package finance.idem.api.tenant

import finance.idem.api.security.TestSecurityConfig
import finance.idem.application.tenant.GetWebhookConfigUseCase
import finance.idem.application.tenant.TenantWebhookConfig
import finance.idem.application.tenant.UpdateWebhookConfigUseCase
import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.util.UUID

@WebMvcTest(TenantController::class)
@Import(TestSecurityConfig::class)
class TenantControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var updateWebhookConfigUseCase: UpdateWebhookConfigUseCase

    @MockitoBean
    lateinit var getWebhookConfigUseCase: GetWebhookConfigUseCase

    private val tenantId = TenantId(UUID.randomUUID())
    private val webhookAuth = TestingAuthenticationToken(tenantId, null, "WEBHOOK_MANAGE")
    private val wrongScopeAuth = TestingAuthenticationToken(tenantId, null, "TRANSACTIONS_READ")

    private val webhookUrl = "https://example.com/hook"
    private val secret = "a".repeat(64)
    private val config = TenantWebhookConfig(webhookUrl, secret)
    private val putBody = """{"webhookUrl":"$webhookUrl"}"""

    // ---- PUT /api/v1/tenant/webhook ----

    @Test
    fun `update returns 200 with secret on valid URL`() {
        whenever(updateWebhookConfigUseCase.execute(any(), any())).thenReturn(Result.success(config))

        mockMvc.put("/api/v1/tenant/webhook") {
            with(authentication(webhookAuth))
            contentType = MediaType.APPLICATION_JSON
            content = putBody
        }.andExpect {
            status { isOk() }
            jsonPath("$.webhookUrl") { value(webhookUrl) }
            jsonPath("$.webhookSecret") { value(secret) }
        }
    }

    @Test
    fun `update returns 400 when URL is invalid`() {
        whenever(updateWebhookConfigUseCase.execute(any(), any()))
            .thenReturn(Result.failure(IllegalArgumentException("blocked private address")))

        mockMvc.put("/api/v1/tenant/webhook") {
            with(authentication(webhookAuth))
            contentType = MediaType.APPLICATION_JSON
            content = putBody
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_WEBHOOK_URL") }
        }
    }

    @Test
    fun `update returns 400 when webhookUrl is blank`() {
        mockMvc.put("/api/v1/tenant/webhook") {
            with(authentication(webhookAuth))
            contentType = MediaType.APPLICATION_JSON
            content = """{"webhookUrl":""}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `update returns 403 when wrong scope`() {
        mockMvc.put("/api/v1/tenant/webhook") {
            with(authentication(wrongScopeAuth))
            contentType = MediaType.APPLICATION_JSON
            content = putBody
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `update returns 401 with no auth`() {
        mockMvc.put("/api/v1/tenant/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = putBody
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    // ---- GET /api/v1/tenant/webhook ----

    @Test
    fun `get returns 200 with masked secret when configured`() {
        whenever(getWebhookConfigUseCase.execute(any())).thenReturn(config)

        mockMvc.get("/api/v1/tenant/webhook") {
            with(authentication(webhookAuth))
        }.andExpect {
            status { isOk() }
            jsonPath("$.webhookUrl") { value(webhookUrl) }
            jsonPath("$.secretPrefix") { value("aaaaaaaa...") }
        }
    }

    @Test
    fun `get returns 404 when not configured`() {
        whenever(getWebhookConfigUseCase.execute(any())).thenReturn(null)

        mockMvc.get("/api/v1/tenant/webhook") {
            with(authentication(webhookAuth))
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("WEBHOOK_NOT_CONFIGURED") }
        }
    }

    @Test
    fun `get returns 403 when wrong scope`() {
        mockMvc.get("/api/v1/tenant/webhook") {
            with(authentication(wrongScopeAuth))
        }.andExpect {
            status { isForbidden() }
        }
    }
}
