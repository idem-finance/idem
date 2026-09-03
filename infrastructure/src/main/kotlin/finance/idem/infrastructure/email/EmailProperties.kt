package finance.idem.infrastructure.email

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `resendApiKey` deliberately not `@NotBlank` — self-hosted/dev environments won't have
 * Resend configured, and welcome-email delivery is best-effort (see [ResendEmailAdapter]).
 */
@ConfigurationProperties("idem.email")
data class EmailProperties(
    val resendApiKey: String = "",
    val fromAddress: String = "noreply@idem.finance",
    // Overridable so tests can point ResendEmailAdapter at a local WireMock instance.
    val baseUrl: String = "https://api.resend.com",
)
