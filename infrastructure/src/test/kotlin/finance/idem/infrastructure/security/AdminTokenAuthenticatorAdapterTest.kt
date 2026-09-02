package finance.idem.infrastructure.security

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminTokenAuthenticatorAdapterTest {
    @Test
    fun `matches the current token`() {
        val authenticator = AdminTokenAuthenticatorAdapter(AdminProperties(token = "current"))

        assertTrue(authenticator.isValid("current"))
    }

    @Test
    fun `matches the previous token during a rotation window`() {
        val authenticator = AdminTokenAuthenticatorAdapter(AdminProperties(token = "current", previousToken = "old"))

        assertTrue(authenticator.isValid("old"))
    }

    @Test
    fun `rejects a token that matches neither current nor previous`() {
        val authenticator = AdminTokenAuthenticatorAdapter(AdminProperties(token = "current", previousToken = "old"))

        assertFalse(authenticator.isValid("neither"))
    }

    @Test
    fun `rejects when the provided token is null or blank`() {
        val authenticator = AdminTokenAuthenticatorAdapter(AdminProperties(token = "current"))

        assertFalse(authenticator.isValid(null))
        assertFalse(authenticator.isValid(""))
    }

    @Test
    fun `rejects everything when the configured token is blank and no previous token is set`() {
        val authenticator = AdminTokenAuthenticatorAdapter(AdminProperties(token = ""))

        assertFalse(authenticator.isValid("anything"))
    }

    @Test
    fun `a blank configured previous token never matches an unrelated non-blank value`() {
        val authenticator = AdminTokenAuthenticatorAdapter(AdminProperties(token = "current", previousToken = ""))

        assertFalse(authenticator.isValid("some-other-value"))
    }
}
