package finance.idem.infrastructure.email

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ResendEmailAdapterTest {
    private lateinit var wireMock: WireMockServer
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setup() {
        wireMock = WireMockServer(options().dynamicPort())
        wireMock.start()
    }

    @AfterEach
    fun teardown() {
        wireMock.stop()
    }

    private fun adapter(resendApiKey: String = "re_test_key") =
        ResendEmailAdapter(
            EmailProperties(resendApiKey = resendApiKey, fromAddress = "noreply@idem.finance", baseUrl = wireMock.baseUrl()),
            objectMapper,
        )

    @Test
    fun `does not call Resend when the API key is blank`() {
        adapter(resendApiKey = "").sendWelcomeEmail("ops@acme.com", "Acme", "sk_live_x", "https://cloud.idem.finance/t/x")

        wireMock.verify(0, postRequestedFor(urlEqualTo("/emails")))
    }

    @Test
    fun `posts to Resend with bearer auth and welcome content when configured`() {
        wireMock.stubFor(post(urlEqualTo("/emails")).willReturn(aResponse().withStatus(200)))

        adapter().sendWelcomeEmail("ops@acme.com", "Acme", "sk_live_x", "https://cloud.idem.finance/t/x")

        wireMock.verify(
            postRequestedFor(urlEqualTo("/emails"))
                .withHeader("Authorization", equalTo("Bearer re_test_key"))
                .withRequestBody(containing("sk_live_x"))
                .withRequestBody(containing("ops@acme.com")),
        )
    }

    @Test
    fun `logs and does not throw when Resend returns a non-2xx status`() {
        wireMock.stubFor(post(urlEqualTo("/emails")).willReturn(aResponse().withStatus(500)))

        adapter().sendWelcomeEmail("ops@acme.com", "Acme", "sk_live_x", "https://cloud.idem.finance/t/x")
    }
}
