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

    @Test
    fun `getPrincipal returns tenantId`() {
        val auth = ApiKeyAuthentication(tenantId, listOf(SimpleGrantedAuthority("TRANSACTIONS_WRITE")))
        assertEquals(tenantId, auth.principal)
    }

    @Test
    fun `getCredentials returns null`() {
        val auth = ApiKeyAuthentication(tenantId, emptyList())
        assertNull(auth.credentials)
    }

    @Test
    fun `isAuthenticated is true immediately after creation`() {
        val auth = ApiKeyAuthentication(tenantId, emptyList())
        assertTrue(auth.isAuthenticated)
    }

    @Test
    fun `authorities are stored correctly`() {
        val scopes = listOf(
            SimpleGrantedAuthority("TRANSACTIONS_READ"),
            SimpleGrantedAuthority("ACCOUNTS_READ"),
        )
        val auth = ApiKeyAuthentication(tenantId, scopes)
        assertEquals(setOf("TRANSACTIONS_READ", "ACCOUNTS_READ"), auth.authorities.map { it.authority }.toSet())
    }
}
