package finance.idem.core.security

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiKeyTest {

    private val tenantId = TenantId.generate()

    @Test
    fun `create builds ApiKey with generated id and correct fields`() {
        val key = ApiKey.create(
            tenantId = tenantId,
            keyHash = "\$2a\$12\$somebcrypthash",
            prefix = "sk_live_a1b2",
            scopes = setOf(ApiScope.TRANSACTIONS_READ, ApiScope.TRANSACTIONS_WRITE),
        )
        assertNotNull(key.id)
        assert(key.tenantId == tenantId)
        assert(key.keyHash == "\$2a\$12\$somebcrypthash")
        assert(key.prefix == "sk_live_a1b2")
        assert(key.scopes == setOf(ApiScope.TRANSACTIONS_READ, ApiScope.TRANSACTIONS_WRITE))
        assertFalse(key.isRevoked)
    }

    @Test
    fun `hasScope returns true for granted scope`() {
        val key = apiKey(setOf(ApiScope.TRANSACTIONS_READ, ApiScope.ACCOUNTS_READ))
        assertTrue(key.hasScope(ApiScope.TRANSACTIONS_READ))
        assertTrue(key.hasScope(ApiScope.ACCOUNTS_READ))
    }

    @Test
    fun `hasScope returns false for scope not in set`() {
        val key = apiKey(setOf(ApiScope.TRANSACTIONS_READ))
        assertFalse(key.hasScope(ApiScope.TRANSACTIONS_WRITE))
        assertFalse(key.hasScope(ApiScope.ADMIN))
    }

    @Test
    fun `isRevoked is false when revokedAt is null`() {
        assertFalse(apiKey().isRevoked)
    }

    @Test
    fun `isRevoked is true when revokedAt is set`() {
        val revoked = apiKey().copy(revokedAt = Instant.now())
        assertTrue(revoked.isRevoked)
    }

    @Test
    fun `ADMIN scope does not implicitly grant other scopes`() {
        val key = apiKey(setOf(ApiScope.ADMIN))
        assertTrue(key.hasScope(ApiScope.ADMIN))
        assertFalse(key.hasScope(ApiScope.TRANSACTIONS_READ))
    }

    @Test
    fun `empty scopes set grants nothing`() {
        val key = apiKey(emptySet())
        ApiScope.entries.forEach { scope -> assertFalse(key.hasScope(scope)) }
    }

    @Test
    fun `ApiKeyId generate produces unique ids`() {
        val a = ApiKeyId.generate()
        val b = ApiKeyId.generate()
        assert(a.value != b.value)
    }

    @Test
    fun `ApiKeyId of parses UUID string`() {
        val uuid = UUID.randomUUID()
        assertEquals(uuid, ApiKeyId.of(uuid.toString()).value)
    }

    private fun apiKey(scopes: Set<ApiScope> = setOf(ApiScope.TRANSACTIONS_READ)) = ApiKey(
        id = ApiKeyId.generate(),
        tenantId = tenantId,
        keyHash = "\$2a\$12\$hash",
        prefix = "sk_live_test",
        scopes = scopes,
        createdAt = Instant.now(),
    )
}
