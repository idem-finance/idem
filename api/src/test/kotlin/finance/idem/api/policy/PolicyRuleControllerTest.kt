package finance.idem.api.policy

import finance.idem.api.security.TestSecurityConfig
import finance.idem.application.agentic.ManagePolicyRulesUseCase
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyRuleId
import finance.idem.core.agentic.PolicyRuleRecord
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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

@WebMvcTest(PolicyRuleController::class)
@Import(TestSecurityConfig::class)
class PolicyRuleControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var managePolicyRulesUseCase: ManagePolicyRulesUseCase

    private val tenantId = TenantId(UUID.randomUUID())
    private val ruleId = PolicyRuleId(UUID.randomUUID())
    private val now = Instant.now()

    private fun adminAuth() = TestingAuthenticationToken(tenantId, null, "ADMIN")

    private fun limitedAuth() = TestingAuthenticationToken(tenantId, null, "TRANSACTIONS_WRITE")

    private fun record(rule: PolicyRule) =
        PolicyRuleRecord(
            id = ruleId,
            agentKeyPrefix = null,
            rule = rule,
            createdAt = now,
        )

    @Test
    fun `POST creates rule and returns 201`() {
        whenever(managePolicyRulesUseCase.create(any(), anyOrNull(), any()))
            .thenReturn(record(PolicyRule.MaxDebitPerSession(MonetaryAmount.of("500"))))

        mockMvc
            .post("/api/v1/admin/policy-rules") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"MAX_DEBIT_PER_SESSION","amount":"500"}"""
                with(authentication(adminAuth()))
            }.andExpect {
                status { isCreated() }
                jsonPath("$.type") { value("MAX_DEBIT_PER_SESSION") }
                jsonPath("$.params.amount") { value("500") }
            }
    }

    @Test
    fun `POST with unknown rule type returns 400`() {
        mockMvc
            .post("/api/v1/admin/policy-rules") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"UNKNOWN_RULE"}"""
                with(authentication(adminAuth()))
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST without ADMIN scope returns 403`() {
        mockMvc
            .post("/api/v1/admin/policy-rules") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"MAX_DEBIT_PER_SESSION","amount":"100"}"""
                with(authentication(limitedAuth()))
            }.andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `GET returns rule list`() {
        whenever(managePolicyRulesUseCase.findAll(any()))
            .thenReturn(listOf(record(PolicyRule.MaxDebitPerHour(MonetaryAmount.of("1000")))))

        mockMvc
            .get("/api/v1/admin/policy-rules") {
                with(authentication(adminAuth()))
            }.andExpect {
                status { isOk() }
                jsonPath("$[0].type") { value("MAX_DEBIT_PER_HOUR") }
            }
    }

    @Test
    fun `DELETE existing rule returns 204`() {
        whenever(managePolicyRulesUseCase.delete(any(), any())).thenReturn(true)

        mockMvc
            .delete("/api/v1/admin/policy-rules/${ruleId.value}") {
                with(authentication(adminAuth()))
            }.andExpect {
                status { isNoContent() }
            }
    }

    @Test
    fun `DELETE non-existent rule returns 404`() {
        whenever(managePolicyRulesUseCase.delete(any(), any())).thenReturn(false)

        mockMvc
            .delete("/api/v1/admin/policy-rules/${UUID.randomUUID()}") {
                with(authentication(adminAuth()))
            }.andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `POST MAX_DEBIT_PER_HOUR creates rule`() {
        whenever(managePolicyRulesUseCase.create(any(), anyOrNull(), any()))
            .thenReturn(record(PolicyRule.MaxDebitPerHour(MonetaryAmount.of("1000"))))

        mockMvc
            .post("/api/v1/admin/policy-rules") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"MAX_DEBIT_PER_HOUR","amount":"1000"}"""
                with(authentication(adminAuth()))
            }.andExpect {
                status { isCreated() }
                jsonPath("$.type") { value("MAX_DEBIT_PER_HOUR") }
            }
    }

    @Test
    fun `POST REQUIRE_HUMAN_APPROVAL_ABOVE creates rule`() {
        whenever(managePolicyRulesUseCase.create(any(), anyOrNull(), any()))
            .thenReturn(record(PolicyRule.RequireHumanApprovalAbove(MonetaryAmount.of("5000"))))

        mockMvc
            .post("/api/v1/admin/policy-rules") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"REQUIRE_HUMAN_APPROVAL_ABOVE","amount":"5000"}"""
                with(authentication(adminAuth()))
            }.andExpect {
                status { isCreated() }
                jsonPath("$.type") { value("REQUIRE_HUMAN_APPROVAL_ABOVE") }
            }
    }

    @Test
    fun `POST FORBIDDEN_ACCOUNT_PAIR creates rule`() {
        val d = AccountId.generate()
        val c = AccountId.generate()
        whenever(managePolicyRulesUseCase.create(any(), anyOrNull(), any()))
            .thenReturn(record(PolicyRule.ForbiddenAccountPair(d, c)))

        mockMvc
            .post("/api/v1/admin/policy-rules") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"FORBIDDEN_ACCOUNT_PAIR","debitAccountId":"${d.value}","creditAccountId":"${c.value}"}"""
                with(authentication(adminAuth()))
            }.andExpect {
                status { isCreated() }
                jsonPath("$.type") { value("FORBIDDEN_ACCOUNT_PAIR") }
                jsonPath("$.params.debitAccountId") { value(d.value.toString()) }
            }
    }

    @Test
    fun `POST ALLOWED_TOKENS creates rule`() {
        whenever(managePolicyRulesUseCase.create(any(), anyOrNull(), any()))
            .thenReturn(record(PolicyRule.AllowedTokens(setOf(StablecoinToken.USDC))))

        mockMvc
            .post("/api/v1/admin/policy-rules") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"ALLOWED_TOKENS","tokens":["USDC"]}"""
                with(authentication(adminAuth()))
            }.andExpect {
                status { isCreated() }
                jsonPath("$.type") { value("ALLOWED_TOKENS") }
            }
    }

    @Test
    fun `POST ALLOWED_CHAINS creates rule`() {
        whenever(managePolicyRulesUseCase.create(any(), anyOrNull(), any()))
            .thenReturn(record(PolicyRule.AllowedChains(setOf(ChainId.EVM))))

        mockMvc
            .post("/api/v1/admin/policy-rules") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"ALLOWED_CHAINS","chains":["EVM"]}"""
                with(authentication(adminAuth()))
            }.andExpect {
                status { isCreated() }
                jsonPath("$.type") { value("ALLOWED_CHAINS") }
            }
    }

    @Test
    fun `POST missing required field returns 400`() {
        mockMvc
            .post("/api/v1/admin/policy-rules") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"MAX_DEBIT_PER_SESSION"}"""
                with(authentication(adminAuth()))
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST ALLOWED_TOKENS with empty array returns 400`() {
        mockMvc
            .post("/api/v1/admin/policy-rules") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"ALLOWED_TOKENS","tokens":[]}"""
                with(authentication(adminAuth()))
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST ALLOWED_CHAINS with empty array returns 400`() {
        mockMvc
            .post("/api/v1/admin/policy-rules") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"ALLOWED_CHAINS","chains":[]}"""
                with(authentication(adminAuth()))
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET returns multiple rules with all types in response`() {
        val d = AccountId.generate()
        val c = AccountId.generate()
        whenever(managePolicyRulesUseCase.findAll(any())).thenReturn(
            listOf(
                record(PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))),
                record(PolicyRule.ForbiddenAccountPair(d, c)),
                record(PolicyRule.AllowedTokens(setOf(StablecoinToken.USDC))),
                record(PolicyRule.AllowedChains(setOf(ChainId.SOLANA))),
            ),
        )

        mockMvc
            .get("/api/v1/admin/policy-rules") {
                with(authentication(adminAuth()))
            }.andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(4) }
                jsonPath("$[1].type") { value("FORBIDDEN_ACCOUNT_PAIR") }
                jsonPath("$[2].type") { value("ALLOWED_TOKENS") }
                jsonPath("$[3].type") { value("ALLOWED_CHAINS") }
            }
    }
}
