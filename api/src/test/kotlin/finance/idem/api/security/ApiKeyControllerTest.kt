package finance.idem.api.security

import finance.idem.application.security.GenerateApiKeyUseCase
import finance.idem.application.security.GeneratedApiKey
import finance.idem.application.security.InsufficientCallerScope
import finance.idem.application.security.ListApiKeysUseCase
import finance.idem.application.security.RevokeApiKeyUseCase
import finance.idem.core.TenantId
import finance.idem.core.security.ApiKey
import finance.idem.core.security.ApiKeyId
import finance.idem.core.security.ApiScope
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

@WebMvcTest(ApiKeyController::class)
@Import(TestSecurityConfig::class)
class ApiKeyControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var generateUseCase: GenerateApiKeyUseCase

    @MockitoBean
    lateinit var listUseCase: ListApiKeysUseCase

    @MockitoBean
    lateinit var revokeUseCase: RevokeApiKeyUseCase

    private val tenantId = TenantId(UUID.randomUUID())
    private val keyId = UUID.randomUUID()

    private fun adminAuth() = TestingAuthenticationToken(tenantId, null, "ADMIN")
    private fun limitedAuth() = TestingAuthenticationToken(tenantId, null, "TRANSACTIONS_WRITE")

    private val createBody = """{"scopes":["TRANSACTIONS_READ"]}"""

    // ---- POST /api/v1/api-keys ----

    @Test
    fun `create returns 201 with rawKey when ADMIN caller requests subset scopes`() {
        val generated = GeneratedApiKey(
            rawKey = "sk_live_abc123",
            apiKey = apiKey(setOf(ApiScope.TRANSACTIONS_READ)),
        )
        whenever(generateUseCase.execute(any())).thenReturn(Result.success(generated))

        mockMvc.post("/api/v1/api-keys") {
            with(authentication(adminAuth()))
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect {
            status { isCreated() }
            jsonPath("$.rawKey") { value("sk_live_abc123") }
            jsonPath("$.prefix") { value("sk_live_test") }
        }
    }

    @Test
    fun `create returns 400 when requested scopes exceed caller scopes`() {
        whenever(generateUseCase.execute(any()))
            .thenReturn(Result.failure(InsufficientCallerScope("excess: [ADMIN]")))

        mockMvc.post("/api/v1/api-keys") {
            with(authentication(adminAuth()))
            contentType = MediaType.APPLICATION_JSON
            content = """{"scopes":["ADMIN","TRANSACTIONS_READ"]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INSUFFICIENT_CALLER_SCOPE") }
        }
    }

    @Test
    fun `create returns 403 when caller lacks ADMIN scope`() {
        mockMvc.post("/api/v1/api-keys") {
            with(authentication(limitedAuth()))
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `create returns 401 with no auth`() {
        mockMvc.post("/api/v1/api-keys") {
            contentType = MediaType.APPLICATION_JSON
            content = createBody
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `create returns 400 when scopes is empty`() {
        mockMvc.post("/api/v1/api-keys") {
            with(authentication(adminAuth()))
            contentType = MediaType.APPLICATION_JSON
            content = """{"scopes":[]}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    // ---- GET /api/v1/api-keys ----

    @Test
    fun `list returns 200 with key summaries`() {
        whenever(listUseCase.execute(tenantId))
            .thenReturn(listOf(apiKey(setOf(ApiScope.TRANSACTIONS_READ))))

        mockMvc.get("/api/v1/api-keys") {
            with(authentication(adminAuth()))
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].prefix") { value("sk_live_test") }
            jsonPath("$[0].rawKey") { doesNotExist() }
        }
    }

    @Test
    fun `list returns 403 when not ADMIN`() {
        mockMvc.get("/api/v1/api-keys") {
            with(authentication(limitedAuth()))
        }.andExpect {
            status { isForbidden() }
        }
    }

    // ---- DELETE /api/v1/api-keys/{keyId} ----

    @Test
    fun `revoke returns 204 when key found`() {
        whenever(revokeUseCase.execute(any(), any())).thenReturn(true)

        mockMvc.delete("/api/v1/api-keys/$keyId") {
            with(authentication(adminAuth()))
        }.andExpect {
            status { isNoContent() }
        }
    }

    @Test
    fun `revoke returns 404 when key not found`() {
        whenever(revokeUseCase.execute(any(), any())).thenReturn(false)

        mockMvc.delete("/api/v1/api-keys/$keyId") {
            with(authentication(adminAuth()))
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("API_KEY_NOT_FOUND") }
        }
    }

    @Test
    fun `revoke returns 403 when not ADMIN`() {
        mockMvc.delete("/api/v1/api-keys/$keyId") {
            with(authentication(limitedAuth()))
        }.andExpect {
            status { isForbidden() }
        }
    }

    private fun apiKey(scopes: Set<ApiScope>) = ApiKey(
        id = ApiKeyId(UUID.randomUUID()),
        tenantId = tenantId,
        keyHash = "\$2a\$12\$fakehash",
        prefix = "sk_live_test",
        scopes = scopes,
        createdAt = Instant.now(),
    )
}
