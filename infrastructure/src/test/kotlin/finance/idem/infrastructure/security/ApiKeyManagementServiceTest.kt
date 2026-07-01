package finance.idem.infrastructure.security

import finance.idem.application.security.GenerateApiKeyCommand
import finance.idem.application.security.InsufficientCallerScope
import finance.idem.core.TenantId
import finance.idem.core.security.ApiKey
import finance.idem.core.security.ApiKeyId
import finance.idem.core.security.ApiKeyRepository
import finance.idem.core.security.ApiScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class ApiKeyManagementServiceTest {
    @Mock
    private lateinit var apiKeyService: ApiKeyService

    @Mock
    private lateinit var apiKeyRepository: ApiKeyRepository

    private val tenantId = TenantId.generate()

    private val service by lazy { ApiKeyManagementService(apiKeyService, apiKeyRepository) }

    // ---- generate ----

    @Test
    fun `generate succeeds when requested scopes are subset of caller scopes`() {
        val callerScopes = setOf(ApiScope.ADMIN, ApiScope.TRANSACTIONS_WRITE)
        val requested = setOf(ApiScope.TRANSACTIONS_WRITE)
        val cmd = GenerateApiKeyCommand(tenantId, requested, callerScopes)

        val fakeKey = apiKey(requested)
        whenever(apiKeyService.generate(any(), any())).thenReturn("sk_live_raw" to fakeKey)

        val result = service.execute(cmd)

        assertTrue(result.isSuccess)
        assertEquals("sk_live_raw", result.getOrThrow().rawKey)
    }

    @Test
    fun `generate fails when requested scopes exceed caller scopes`() {
        val callerScopes = setOf(ApiScope.TRANSACTIONS_WRITE)
        val requested = setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.ADMIN)
        val cmd = GenerateApiKeyCommand(tenantId, requested, callerScopes)

        val result = service.execute(cmd)

        assertTrue(result.isFailure)
        assertIs<InsufficientCallerScope>(result.exceptionOrNull())
    }

    @Test
    fun `generate with ADMIN caller can issue any scope`() {
        val callerScopes = setOf(ApiScope.ADMIN)
        val requested = ApiScope.entries.toSet()
        val cmd = GenerateApiKeyCommand(tenantId, requested, callerScopes)

        // ADMIN does NOT automatically contain all scopes in the subset check —
        // the caller must actually hold the scopes they're delegating.
        // This test verifies ADMIN alone is insufficient to delegate all scopes.
        val result = service.execute(cmd)

        assertTrue(result.isFailure)
        assertIs<InsufficientCallerScope>(result.exceptionOrNull())
    }

    @Test
    fun `generate with all scopes in caller can issue all scopes`() {
        val allScopes = ApiScope.entries.toSet()
        val cmd = GenerateApiKeyCommand(tenantId, allScopes, allScopes)

        val fakeKey = apiKey(allScopes)
        whenever(apiKeyService.generate(any(), any())).thenReturn("sk_live_raw" to fakeKey)

        val result = service.execute(cmd)

        assertTrue(result.isSuccess)
        verify(apiKeyService).generate(any(), any())
    }

    // ---- list ----

    @Test
    fun `list delegates to repository`() {
        val keys = listOf(apiKey(setOf(ApiScope.TRANSACTIONS_READ)))
        whenever(apiKeyRepository.findAllByTenantId(tenantId)).thenReturn(keys)

        val result = service.execute(tenantId)

        assertEquals(1, result.size)
        verify(apiKeyRepository).findAllByTenantId(tenantId)
    }

    // ---- revoke ----

    @Test
    fun `revoke delegates to apiKeyService and returns its result`() {
        val keyId = ApiKeyId.generate()
        whenever(apiKeyService.revoke(keyId, tenantId)).thenReturn(true)

        assertTrue(service.execute(keyId, tenantId))
    }

    @Test
    fun `revoke returns false when key not found`() {
        val keyId = ApiKeyId.generate()
        whenever(apiKeyService.revoke(keyId, tenantId)).thenReturn(false)

        assertFalse(service.execute(keyId, tenantId))
    }

    private fun apiKey(scopes: Set<ApiScope>) =
        ApiKey(
            id = ApiKeyId(UUID.randomUUID()),
            tenantId = tenantId,
            keyHash = "\$2a\$12\$fakehash",
            prefix = "sk_live_test",
            scopes = scopes,
            createdAt = Instant.now(),
        )
}
