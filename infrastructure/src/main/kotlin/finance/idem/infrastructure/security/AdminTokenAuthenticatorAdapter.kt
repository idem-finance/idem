package finance.idem.infrastructure.security

import finance.idem.application.port.AdminTokenAuthenticator
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * Constant-time validation of the internal admin token (#272), shared by
 * [finance.idem.api.internal.AdminTenantController] (the primary, ordering-correct gate —
 * checked before request-body validation, see the controller's KDoc) and
 * [finance.idem.infrastructure.service.TenantProvisioningService] (defense-in-depth at the
 * point of mutation, same "gate at the edge, re-check at the point of mutation" pattern as
 * `@PreAuthorize` + RLS elsewhere in this codebase). Both depend on the [AdminTokenAuthenticator]
 * port (application module) rather than this class directly — `api` cannot depend on
 * `infrastructure` per this repo's module rules.
 */
@Component
class AdminTokenAuthenticatorAdapter(
    private val properties: AdminProperties,
) : AdminTokenAuthenticator {
    override fun isValid(provided: String?): Boolean {
        if (provided.isNullOrBlank()) return false
        if (properties.token.isNotBlank() && constantTimeEquals(properties.token, provided)) return true
        val previous = properties.previousToken
        return !previous.isNullOrBlank() && constantTimeEquals(previous, provided)
    }

    private fun constantTimeEquals(
        expected: String,
        provided: String,
    ): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8),
        )
}
