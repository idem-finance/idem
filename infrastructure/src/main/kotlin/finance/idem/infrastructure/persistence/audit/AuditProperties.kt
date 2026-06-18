package finance.idem.infrastructure.persistence.audit

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("idem.audit")
data class AuditProperties(
    @field:NotBlank(message = "idem.audit.hmac-secret must not be blank — set IDEM_AUDIT_HMAC_SECRET in production")
    val hmacSecret: String = "",
)
