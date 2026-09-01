package finance.idem.infrastructure.service

import finance.idem.application.port.EmailSender
import finance.idem.application.port.TenantRepository
import finance.idem.application.tenant.InvalidAdminToken
import finance.idem.application.tenant.ProvisionTenantCommand
import finance.idem.application.tenant.TenantNotFound
import finance.idem.core.TenantId
import finance.idem.core.security.ApiKey
import finance.idem.core.security.ApiScope
import finance.idem.core.tenant.Tenant
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.core.tenant.TenantPlan
import finance.idem.infrastructure.security.AdminProperties
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    private val adminProperties = AdminProperties(token = "correct-token")
    private val dashboardProperties = DashboardProperties(baseUrl = "https://cloud.idem.finance")

    private val service by lazy {
        TenantProvisioningService(
            tenantRepository,
            tenantConfigRepository,
            apiKeyService,
            emailSender,
            adminProperties,
            dashboardProperties,
        )
    }

    private fun command(
        adminToken: String? = "correct-token",
        plan: TenantPlan = TenantPlan.CLOUD,
    ) = ProvisionTenantCommand(
        adminToken = adminToken,
        organizationName = "Acme",
        contactEmail = "ops@acme.com",
        plan = plan,
    )

    @Test
    fun `provision creates tenant, config, key and sends email on success`() {
        whenever(apiKeyService.generate(any(), eq(ApiScope.entries.toSet())))
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

        verify(emailSender).sendWelcomeEmail(eq("ops@acme.com"), eq("Acme"), eq("sk_live_rawkey"), any())
    }

    @Test
    fun `provision fails closed when admin token is blank`() {
        val result = service.execute(command(adminToken = null))

        assertTrue(result.isFailure)
        assertIs<InvalidAdminToken>(result.exceptionOrNull())
        verify(tenantRepository, never()).create(any())
    }

    @Test
    fun `provision fails closed when admin token does not match`() {
        val result = service.execute(command(adminToken = "wrong-token"))

        assertTrue(result.isFailure)
        assertIs<InvalidAdminToken>(result.exceptionOrNull())
        verify(tenantRepository, never()).create(any())
    }

    @Test
    fun `provision fails closed when the configured admin token itself is blank`() {
        val blankTokenService =
            TenantProvisioningService(
                tenantRepository,
                tenantConfigRepository,
                apiKeyService,
                emailSender,
                AdminProperties(token = ""),
                dashboardProperties,
            )

        val result = blankTokenService.execute(command(adminToken = "anything"))

        assertTrue(result.isFailure)
        assertIs<InvalidAdminToken>(result.exceptionOrNull())
    }

    @Test
    fun `provision still succeeds when the welcome email fails`() {
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
        val tenantId = TenantId.generate()
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(null)

        val result = service.execute("correct-token", tenantId)

        assertTrue(result.isFailure)
        assertIs<TenantNotFound>(result.exceptionOrNull())
        verify(tenantConfigRepository, never()).upsert(any())
    }

    @Test
    fun `suspend sets suspendedAt while preserving the rest of the config`() {
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
    fun `suspend fails closed with a wrong admin token`() {
        val result = service.execute("wrong-token", TenantId.generate())

        assertTrue(result.isFailure)
        assertIs<InvalidAdminToken>(result.exceptionOrNull())
        verify(tenantConfigRepository, never()).findByTenantId(any())
    }
}
