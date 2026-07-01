package finance.idem.api.reconciliation

import finance.idem.api.security.TestSecurityConfig
import finance.idem.application.reconciliation.ReconcileBatchItemResult
import finance.idem.application.reconciliation.ReconcileBatchUseCase
import finance.idem.application.reconciliation.ReconcileOutcome
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
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
import org.springframework.test.web.servlet.post
import java.util.UUID

@WebMvcTest(ReconciliationController::class)
@Import(TestSecurityConfig::class)
class ReconciliationControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var reconcileBatchUseCase: ReconcileBatchUseCase

    private val tenantId = TenantId(UUID.randomUUID())
    private val txId = TransactionId.generate()
    private val reconcileAuth = TestingAuthenticationToken(tenantId, null, "RECONCILIATION_WRITE")
    private val wrongScopeAuth = TestingAuthenticationToken(tenantId, null, "TRANSACTIONS_READ")

    @Test
    fun `batch returns 200 with outcomes`() {
        whenever(reconcileBatchUseCase.execute(any()))
            .thenReturn(listOf(ReconcileBatchItemResult(txId, ReconcileOutcome.SETTLED)))

        mockMvc
            .post("/api/v1/reconciliation/batch") {
                with(authentication(reconcileAuth))
                contentType = MediaType.APPLICATION_JSON
                content = """{"transactionIds":["${txId.value}"]}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$[0].transactionId") { value(txId.value.toString()) }
                jsonPath("$[0].outcome") { value("SETTLED") }
            }
    }

    @Test
    fun `batch returns 400 for empty list`() {
        mockMvc
            .post("/api/v1/reconciliation/batch") {
                with(authentication(reconcileAuth))
                contentType = MediaType.APPLICATION_JSON
                content = """{"transactionIds":[]}"""
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `batch returns 403 when wrong scope`() {
        mockMvc
            .post("/api/v1/reconciliation/batch") {
                with(authentication(wrongScopeAuth))
                contentType = MediaType.APPLICATION_JSON
                content = """{"transactionIds":["${txId.value}"]}"""
            }.andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `batch returns 401 with no auth`() {
        mockMvc
            .post("/api/v1/reconciliation/batch") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"transactionIds":["${txId.value}"]}"""
            }.andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `batch returns NOT_FOUND outcome for unknown transaction`() {
        whenever(reconcileBatchUseCase.execute(any()))
            .thenReturn(listOf(ReconcileBatchItemResult(txId, ReconcileOutcome.NOT_FOUND)))

        mockMvc
            .post("/api/v1/reconciliation/batch") {
                with(authentication(reconcileAuth))
                contentType = MediaType.APPLICATION_JSON
                content = """{"transactionIds":["${txId.value}"]}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$[0].outcome") { value("NOT_FOUND") }
            }
    }
}
