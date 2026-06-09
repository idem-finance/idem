package finance.idem.api.internal

import finance.idem.application.chain.AlchemyWebhookPort
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

@WebMvcTest(AlchemyWebhookController::class)
class AlchemyWebhookControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var alchemyWebhookPort: AlchemyWebhookPort

    @Test
    fun `returns 401 when port signals authentication failure`() {
        whenever(alchemyWebhookPort.handle(any(), any()))
            .thenReturn(Result.failure(IllegalArgumentException("bad sig")))

        mockMvc.post("/internal/webhooks/alchemy") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"ADDRESS_ACTIVITY"}"""
            header("X-Alchemy-Signature", "deadbeef")
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `returns 200 when port processes successfully`() {
        whenever(alchemyWebhookPort.handle(any(), any()))
            .thenReturn(Result.success(Unit))

        mockMvc.post("/internal/webhooks/alchemy") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"ADDRESS_ACTIVITY","event":{"network":"ETH_MAINNET","activity":[]}}"""
            header("X-Alchemy-Signature", "valid-hmac")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `returns 200 when signature header is absent and port succeeds (dev mode)`() {
        whenever(alchemyWebhookPort.handle(isNull(), any()))
            .thenReturn(Result.success(Unit))

        mockMvc.post("/internal/webhooks/alchemy") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"ADDRESS_ACTIVITY","event":{"network":"ETH_MAINNET","activity":[]}}"""
        }.andExpect {
            status { isOk() }
        }
    }
}
