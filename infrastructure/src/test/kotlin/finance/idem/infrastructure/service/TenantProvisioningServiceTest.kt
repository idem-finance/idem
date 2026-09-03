package finance.idem.infrastructure.service

import finance.idem.application.port.AdminTokenAuthenticator
import finance.idem.application.port.EmailSender
import finance.idem.application.port.TenantProvisioningIdempotencyStore
import finance.idem.application.port.TenantRepository
import finance.idem.application.tenant.InvalidAdminToken
import finance.idem.application.tenant.ProvisionTenantCommand
import finance.idem.application.tenant.ProvisionedTenant
import finance.idem.application.tenant.ProvisioningInProgress
import finance.idem.application.tenant.TenantNotFound
import finance.idem.core.TenantId
import finance.idem.core.security.ApiKey
import finance.idem.core.security.ApiScope
import finance.idem.core.tenant.Tenant
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.core.tenant.TenantPlan
import finance.idem.infrastructure.security.ApiKeyService
import finance.idem.infrastructure.tenant.DashboardProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Minimal fake — lets TransactionTemplate.execute { } run its callback without a real DB. */
private class NoopTransactionManager : PlatformTransactionManager {
    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

    override fun commit(status: TransactionStatus) {}

    override fun rollback(status: TransactionStatus) {}
}

@ExtendWith(MockitoExtension::class)
class TenantProvisioningServiceTest {
    @Mock
    private lateinit var tenantRepository: TenantRepository

    @Mock
    private lateinit var tenantConfigRepository: TenantConfigRepository

    @Mock
    private lateinit var apiKeyService: ApiKeyService

    @Mock
    private lateinit var emailSender: EmailSender

    @Mock
    private lateinit var idempotencyStore: TenantProvisioningIdempotencyStore

    @Mock
    private lateinit var adminTokenAuthenticator: AdminTokenAuthenticator

    private val dashboardProperties = DashboardProperties(baseUrl = "https://cloud.idem.finance")

    private val service by lazy {
        TenantProvisioningService(
            tenantRepository,
            tenantConfigRepository,
            apiKeyService,
            emailSender,
            idempotencyStore,
            adminTokenAuthenticator,
            dashboardProperties,
            NoopTransactionManager(),
        )
    }

    private fun command(
        adminToken: String? = "correct-token",
        idempotencyKey: String = "idem-key-1",
    ) = ProvisionTenantCommand(
        adminToken = adminToken,
        idempotencyKey = idempotencyKey,
        organizationName = "Acme",
        contactEmail = "ops@acme.com",
    )

    private fun stubValidToken(valid: Boolean = true) {
        whenever(adminTokenAuthenticator.isValid(any())).thenReturn(valid)
    }

    private fun stubFreshClaim() {
        whenever(idempotencyStore.claim(any())).thenReturn(true)
    }

    @Test
    fun `provision creates tenant, config, key with the default scope set, and sends email on success`() {
        stubValidToken()
        stubFreshClaim()
        whenever(apiKeyService.generate(any(), any()))
            .thenReturn(
                "sk_live_rawkey" to
                    ApiKey.create(
                        tenantId = TenantId.generate(),
                        keyHash = "hash",
                        prefix = "sk_live_rawk",
                        scopes = ApiScope.entries.toSet(),
                    ),
            )

        val result = service.execute(command())

        assertTrue(result.isSuccess)
        val provisioned = result.getOrThrow()
        assertEquals("sk_live_rawkey", provisioned.rawApiKey)
        assertTrue(provisioned.dashboardUrl.startsWith("https://cloud.idem.finance/t/"))

        val tenantCaptor = argumentCaptor<Tenant>()
        verify(tenantRepository).create(tenantCaptor.capture())
        assertEquals("Acme", tenantCaptor.firstValue.organizationName)
        assertEquals("ops@acme.com", tenantCaptor.firstValue.contactEmail)

        val configCaptor = argumentCaptor<TenantConfig>()
        verify(tenantConfigRepository).upsert(configCaptor.capture())
        assertEquals(TenantPlan.CLOUD, configCaptor.firstValue.plan)
        assertEquals(100, configCaptor.firstValue.rateLimitPerSecond)
        assertEquals(1000, configCaptor.firstValue.rateLimitPerMinute)

        val scopesCaptor = argumentCaptor<Set<ApiScope>>()
        verify(apiKeyService).generate(any(), scopesCaptor.capture())
        assertTrue(ApiScope.ADMIN !in scopesCaptor.firstValue)
        assertTrue(ApiScope.AGENTS_ROLLBACK !in scopesCaptor.firstValue)
        assertEquals(ApiScope.entries.toSet() - setOf(ApiScope.ADMIN, ApiScope.AGENTS_ROLLBACK), scopesCaptor.firstValue)

        verify(emailSender).sendWelcomeEmail(eq("ops@acme.com"), eq("Acme"), eq("sk_live_rawkey"), any())
        verify(idempotencyStore).cache(eq("idem-key-1"), any())
    }

    @Test
    fun `provision fails closed when admin token is invalid`() {
        stubValidToken(false)

        val result = service.execute(command())

        assertTrue(result.isFailure)
        assertIs<InvalidAdminToken>(result.exceptionOrNull())
        verify(tenantRepository, never()).create(any())
        verify(idempotencyStore, never()).claim(any())
    }

    @Test
    fun `provision replays the cached result when the idempotency key was already claimed`() {
        stubValidToken()
        val tenantId = TenantId.generate()
        val cached = ProvisionedTenant(tenantId, "sk_live_cached", "https://cloud.idem.finance/t/${tenantId.value}")
        whenever(idempotencyStore.claim(any())).thenReturn(false)
        whenever(idempotencyStore.findCached("idem-key-1")).thenReturn(cached)

        val result = service.execute(command())

        assertTrue(result.isSuccess)
        assertEquals(cached, result.getOrThrow())
        verify(tenantRepository, never()).create(any())
    }

    @Test
    fun `provision fails with ProvisioningInProgress when a concurrent claim hasn't resolved yet`() {
        stubValidToken()
        whenever(idempotencyStore.claim(any())).thenReturn(false)
        whenever(idempotencyStore.findCached("idem-key-1")).thenReturn(null)

        val result = service.execute(command())

        assertTrue(result.isFailure)
        assertIs<ProvisioningInProgress>(result.exceptionOrNull())
    }

    @Test
    fun `provision releases the idempotency claim when the DB transaction fails`() {
        stubValidToken()
        stubFreshClaim()
        whenever(tenantRepository.create(any())).thenThrow(RuntimeException("DB down"))

        val result = service.execute(command())

        assertTrue(result.isFailure)
        verify(idempotencyStore).release("idem-key-1")
        verify(idempotencyStore, never()).cache(any(), any())
        verify(emailSender, never()).sendWelcomeEmail(any(), any(), any(), any())
    }

    @Test
    fun `provision still succeeds when the welcome email fails`() {
        stubValidToken()
        stubFreshClaim()
        whenever(apiKeyService.generate(any(), any()))
            .thenReturn(
                "sk_live_rawkey" to
                    ApiKey.create(
                        tenantId = TenantId.generate(),
                        keyHash = "hash",
                        prefix = "sk_live_rawk",
                        scopes = ApiScope.entries.toSet(),
                    ),
            )
        whenever(emailSender.sendWelcomeEmail(any(), any(), any(), any())).thenThrow(RuntimeException("Resend down"))

        val result = service.execute(command())

        assertTrue(result.isSuccess)
    }

    @Test
    fun `suspend fails for an unknown tenant`() {
        stubValidToken()
        val tenantId = TenantId.generate()
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(null)

        val result = service.execute("correct-token", tenantId)

        assertTrue(result.isFailure)
        assertIs<TenantNotFound>(result.exceptionOrNull())
        verify(tenantConfigRepository, never()).upsert(any())
    }

    @Test
    fun `suspend sets suspendedAt while preserving the rest of the config`() {
        stubValidToken()
        val tenantId = TenantId.generate()
        val existing =
            TenantConfig(
                tenantId = tenantId,
                plan = TenantPlan.CLOUD,
                rateLimitPerSecond = 100,
                rateLimitPerMinute = 1000,
                featureFlags = setOf("compliance_export"),
                hmacKey = "tenant-hmac",
                billingCustomerId = "cus_123",
                createdAt = Instant.now(),
                suspendedAt = null,
            )
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(existing)

        val result = service.execute("correct-token", tenantId)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<TenantConfig>()
        verify(tenantConfigRepository).upsert(captor.capture())
        assertEquals(result.getOrThrow(), captor.firstValue.suspendedAt)
        assertEquals("tenant-hmac", captor.firstValue.hmacKey)
        assertEquals(setOf("compliance_export"), captor.firstValue.featureFlags)
    }

    @Test
    fun `suspend fails closed with an invalid admin token`() {
        stubValidToken(false)

        val result = service.execute("wrong-token", TenantId.generate())

        assertTrue(result.isFailure)
        assertIs<InvalidAdminToken>(result.exceptionOrNull())
        verify(tenantConfigRepository, never()).findByTenantId(any())
    }
}
