package finance.idem.application.security

import finance.idem.core.TenantId
import finance.idem.core.security.ApiKey
import finance.idem.core.security.ApiKeyId
import finance.idem.core.security.ApiScope
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApiKeyManagementModelsTest {
    private val tenantId = TenantId.generate()

    @Test
    fun `GenerateApiKeyCommand holds all fields`() {
        val requested = setOf(ApiScope.TRANSACTIONS_READ)
        val caller = setOf(ApiScope.ADMIN, ApiScope.TRANSACTIONS_READ)
        val cmd = GenerateApiKeyCommand(tenantId, requested, caller)

        assertEquals(tenantId, cmd.tenantId)
        assertEquals(requested, cmd.requestedScopes)
        assertEquals(caller, cmd.callerScopes)
        assertEquals(cmd, cmd.copy())
    }

    @Test
    fun `GeneratedApiKey holds rawKey and apiKey`() {
        val key = apiKey()
        val generated = GeneratedApiKey("sk_live_abc", key)

        assertEquals("sk_live_abc", generated.rawKey)
        assertEquals(key, generated.apiKey)
        assertEquals(generated, generated.copy())
    }

    @Test
    fun `InsufficientCallerScope carries message and is an Exception`() {
        val error = InsufficientCallerScope("excess: [ADMIN]")

        assertIs<Exception>(error)
        assertEquals("excess: [ADMIN]", error.message)
    }

    private fun apiKey() =
        ApiKey(
            id = ApiKeyId(UUID.randomUUID()),
            tenantId = tenantId,
            keyHash = "\$2a\$12\$fakehash",
            prefix = "sk_live_test",
            scopes = setOf(ApiScope.TRANSACTIONS_READ),
            createdAt = Instant.now(),
        )
}
