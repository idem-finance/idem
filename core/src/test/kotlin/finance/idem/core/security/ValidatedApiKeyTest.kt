package finance.idem.core.security

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidatedApiKeyTest {

    private val tenantId = TenantId.generate()

    @Test
    fun `hasScope returns true for granted scope`() {
        val key = ValidatedApiKey(tenantId, setOf(ApiScope.TRANSACTIONS_READ, ApiScope.ACCOUNTS_READ))
        assertTrue(key.hasScope(ApiScope.TRANSACTIONS_READ))
        assertTrue(key.hasScope(ApiScope.ACCOUNTS_READ))
    }

    @Test
    fun `hasScope returns false for missing scope`() {
        val key = ValidatedApiKey(tenantId, setOf(ApiScope.TRANSACTIONS_READ))
        assertFalse(key.hasScope(ApiScope.TRANSACTIONS_WRITE))
        assertFalse(key.hasScope(ApiScope.ADMIN))
    }

    @Test
    fun `empty scopes grants nothing`() {
        val key = ValidatedApiKey(tenantId, emptySet())
        ApiScope.entries.forEach { scope -> assertFalse(key.hasScope(scope)) }
    }
}
