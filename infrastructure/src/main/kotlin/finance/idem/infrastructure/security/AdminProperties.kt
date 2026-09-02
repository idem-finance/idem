package finance.idem.infrastructure.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Deliberately NOT `@Validated`/`@NotBlank` — unlike [finance.idem.infrastructure.persistence.audit.AuditProperties],
 * the internal admin-provisioning endpoint is Cloud-only. Self-hosted installs must keep
 * starting with no `IDEM_ADMIN_TOKEN` set; a blank token instead makes every admin-token
 * check fail closed at request time (see [AdminTokenAuthenticator]).
 *
 * [previousToken] supports zero-downtime rotation: set it to the old token value alongside
 * a new [token], redeploy, then clear it once every caller has picked up the new token.
 */
@ConfigurationProperties("idem.admin")
data class AdminProperties(
    val token: String = "",
    val previousToken: String? = null,
)
