package finance.idem.infrastructure.security

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiKeyAuthenticationTest {
    private val tenantId = TenantId(UUID.randomUUID())
    private val keyPrefix = "sk_live_abcd"

    @Test
    fun `getPrincipal returns tenantId`() {
        val auth = ApiKeyAuthentication(tenantId, keyPrefix, listOf(SimpleGrantedAuthority("TRANSACTIONS_WRITE")))
        assertEquals(tenantId, auth.principal)
    }

    @Test
    fun `getCredentials returns null`() {
        val auth = ApiKeyAuthentication(tenantId, keyPrefix, emptyList())
        assertNull(auth.credentials)
    }

    @Test
    fun `isAuthenticated is true immediately after creation`() {
        val auth = ApiKeyAuthentication(tenantId, keyPrefix, emptyList())
        assertTrue(auth.isAuthenticated)
    }

    @Test
    fun `authorities are stored correctly`() {
        val scopes =
            listOf(
                SimpleGrantedAuthority("TRANSACTIONS_READ"),
                SimpleGrantedAuthority("ACCOUNTS_READ"),
            )
        val auth = ApiKeyAuthentication(tenantId, keyPrefix, scopes)
        assertEquals(setOf("TRANSACTIONS_READ", "ACCOUNTS_READ"), auth.authorities.map { it.authority }.toSet())
    }

    @Test
    fun `getName returns keyPrefix`() {
        val auth = ApiKeyAuthentication(tenantId, "sk_live_xyz1", emptyList())
        assertEquals("sk_live_xyz1", auth.name)
    }

    @Test
    fun `keyPrefix is stored and accessible`() {
        val auth = ApiKeyAuthentication(tenantId, keyPrefix, emptyList())
        assertEquals(keyPrefix, auth.keyPrefix)
    }
}
