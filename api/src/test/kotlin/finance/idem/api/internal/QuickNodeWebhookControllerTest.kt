package finance.idem.api.internal

import finance.idem.application.chain.QuickNodeWebhookUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.isNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(QuickNodeWebhookController::class)
@AutoConfigureMockMvc(addFilters = false)
class QuickNodeWebhookControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var quickNodeWebhookUseCase: QuickNodeWebhookUseCase

    @Test
    fun `returns 401 when use case signals authentication failure`() {
        whenever(quickNodeWebhookUseCase.handle(any(), any(), any(), any()))
            .thenReturn(Result.failure(IllegalArgumentException("bad sig")))

        mockMvc.post("/internal/webhooks/quicknode") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"data":[{"signature":"abc","slot":1,"network":"mainnet-beta"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""
            header("X-QN-Signature", "deadbeef")
            header("X-QN-Nonce", "test-nonce-123")
            header("X-QN-Timestamp", "1718000000")
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `returns 200 when use case processes successfully`() {
        whenever(quickNodeWebhookUseCase.handle(any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))

        mockMvc.post("/internal/webhooks/quicknode") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"data":[{"signature":"abc","slot":1,"network":"mainnet-beta"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""
            header("X-QN-Signature", "valid-hmac")
            header("X-QN-Nonce", "test-nonce-123")
            header("X-QN-Timestamp", "1718000000")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `returns 200 when signature, nonce and timestamp headers are absent and use case succeeds (dev mode)`() {
        whenever(quickNodeWebhookUseCase.handle(isNull(), isNull(), isNull(), any()))
            .thenReturn(Result.success(Unit))

        mockMvc.post("/internal/webhooks/quicknode") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"data":[{"signature":"abc","slot":1,"network":"mainnet-beta"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""
        }.andExpect {
            status { isOk() }
        }
    }
}
