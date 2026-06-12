package finance.idem.api.internal

import finance.idem.application.chain.QuickNodeWebhookPort
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.isNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(QuickNodeWebhookController::class)
class QuickNodeWebhookControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var port: QuickNodeWebhookPort

    @Test
    fun `returns 401 when port signals authentication failure`() {
        whenever(port.handle(any(), any()))
            .thenReturn(Result.failure(IllegalArgumentException("bad sig")))

        mockMvc.post("/internal/webhooks/quicknode") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"data":[{"signature":"abc","slot":1,"network":"mainnet-beta"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""
            header("X-QN-Signature", "deadbeef")
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `returns 200 when port processes successfully`() {
        whenever(port.handle(any(), any()))
            .thenReturn(Result.success(Unit))

        mockMvc.post("/internal/webhooks/quicknode") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"data":[{"signature":"abc","slot":1,"network":"mainnet-beta"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""
            header("X-QN-Signature", "valid-hmac")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `returns 200 when signature header is absent and port succeeds (dev mode)`() {
        whenever(port.handle(isNull(), any()))
            .thenReturn(Result.success(Unit))

        mockMvc.post("/internal/webhooks/quicknode") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"data":[{"signature":"abc","slot":1,"network":"mainnet-beta"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""
        }.andExpect {
            status { isOk() }
        }
    }
}
