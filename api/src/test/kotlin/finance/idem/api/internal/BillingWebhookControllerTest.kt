package finance.idem.api.internal

import finance.idem.application.billing.BillingWebhookUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(BillingWebhookController::class)
@AutoConfigureMockMvc(addFilters = false)
class BillingWebhookControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var billingWebhookUseCase: BillingWebhookUseCase

    @Test
    fun `returns 401 when use case signals authentication failure`() {
        whenever(billingWebhookUseCase.handle(any(), any()))
            .thenReturn(Result.failure(IllegalArgumentException("bad sig")))

        mockMvc
            .post("/internal/webhooks/billing") {
                content = """{"tenantId":"11111111-1111-1111-1111-111111111111"}"""
                header("X-Idem-Signature", "deadbeef")
            }.andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `returns 200 when use case processes successfully`() {
        whenever(billingWebhookUseCase.handle(any(), any()))
            .thenReturn(Result.success(Unit))

        mockMvc
            .post("/internal/webhooks/billing") {
                content = """{"tenantId":"11111111-1111-1111-1111-111111111111"}"""
                header("X-Idem-Signature", "valid-hmac")
            }.andExpect {
                status { isOk() }
            }
    }
}
