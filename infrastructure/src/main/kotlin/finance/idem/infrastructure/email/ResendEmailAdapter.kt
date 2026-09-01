package finance.idem.infrastructure.email

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.application.port.EmailSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class ResendEmailAdapter(
    private val properties: EmailProperties,
    private val objectMapper: ObjectMapper,
) : EmailSender {
    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendWelcomeEmail(
        to: String,
        organizationName: String,
        rawApiKey: String,
        dashboardUrl: String,
    ) {
        if (properties.resendApiKey.isBlank()) {
            log.warn("Resend API key not configured — skipping welcome email to {}", to)
            return
        }

        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "from" to properties.fromAddress,
                    "to" to listOf(to),
                    "subject" to "Welcome to Idem Cloud, $organizationName",
                    "html" to
                        """
                        <p>Your Idem Cloud tenant is ready.</p>
                        <p>API key (shown once — store it securely): <code>$rawApiKey</code></p>
                        <p>Dashboard: <a href="$dashboardUrl">$dashboardUrl</a></p>
                        """.trimIndent(),
                ),
            )

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("${properties.baseUrl}/emails"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${properties.resendApiKey}")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            log.warn("ResendEmailAdapter: welcome email to {} failed with HTTP {}", to, response.statusCode())
        }
    }
}
