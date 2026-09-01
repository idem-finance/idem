package finance.idem.infrastructure.service

import finance.idem.application.port.EmailSender
import finance.idem.application.port.TenantRepository
import finance.idem.application.tenant.InvalidAdminToken
import finance.idem.application.tenant.ProvisionTenantCommand
import finance.idem.application.tenant.ProvisionTenantUseCase
import finance.idem.application.tenant.ProvisionedTenant
import finance.idem.application.tenant.SuspendTenantUseCase
import finance.idem.application.tenant.TenantNotFound
import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope
import finance.idem.core.tenant.Tenant
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.core.tenant.TenantPlan
import finance.idem.infrastructure.security.AdminProperties
import finance.idem.infrastructure.security.ApiKeyService
import finance.idem.infrastructure.tenant.DashboardProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant

@Service
class TenantProvisioningService(
    private val tenantRepository: TenantRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val apiKeyService: ApiKeyService,
    private val emailSender: EmailSender,
    private val adminProperties: AdminProperties,
    private val dashboardProperties: DashboardProperties,
) : ProvisionTenantUseCase,
    SuspendTenantUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun execute(cmd: ProvisionTenantCommand): Result<ProvisionedTenant> {
        if (!isValidAdminToken(cmd.adminToken)) {
            return Result.failure(InvalidAdminToken("Missing or invalid internal admin token"))
        }

        val tenantId = TenantId.generate()
        val now = Instant.now()
        tenantRepository.create(
            Tenant(
                id = tenantId,
                organizationName = cmd.organizationName,
                contactEmail = cmd.contactEmail,
                createdAt = now,
            ),
        )

        val (rateLimitPerSecond, rateLimitPerMinute) = defaultRateLimits(cmd.plan)
        tenantConfigRepository.upsert(
            TenantConfig(
                tenantId = tenantId,
                plan = cmd.plan,
                rateLimitPerSecond = rateLimitPerSecond,
                rateLimitPerMinute = rateLimitPerMinute,
                featureFlags = emptySet(),
                hmacKey = null,
                billingCustomerId = null,
                createdAt = now,
                suspendedAt = null,
            ),
        )

        val (rawKey, _) = apiKeyService.generate(tenantId, ApiScope.entries.toSet())
        val dashboardUrl = "${dashboardProperties.baseUrl}/t/${tenantId.value}"

        runCatching {
            emailSender.sendWelcomeEmail(cmd.contactEmail, cmd.organizationName, rawKey, dashboardUrl)
        }.onFailure { e ->
            log.warn("Welcome email failed for tenant {} — provisioning still succeeds", tenantId.value, e)
        }

        return Result.success(ProvisionedTenant(tenantId, rawKey, dashboardUrl))
    }

    @Transactional
    override fun execute(
        adminToken: String?,
        tenantId: TenantId,
    ): Result<Instant> {
        if (!isValidAdminToken(adminToken)) {
            return Result.failure(InvalidAdminToken("Missing or invalid internal admin token"))
        }

        val existing =
            tenantConfigRepository.findByTenantId(tenantId)
                ?: return Result.failure(TenantNotFound("No tenant found for id=${tenantId.value}"))

        val suspendedAt = Instant.now()
        tenantConfigRepository.upsert(existing.copy(suspendedAt = suspendedAt))
        return Result.success(suspendedAt)
    }

    private fun isValidAdminToken(provided: String?): Boolean {
        if (adminProperties.token.isBlank() || provided.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            adminProperties.token.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8),
        )
    }

    private fun defaultRateLimits(plan: TenantPlan): Pair<Int?, Int?> =
        when (plan) {
            TenantPlan.CLOUD -> CLOUD_RATE_LIMIT_PER_SECOND to CLOUD_RATE_LIMIT_PER_MINUTE
            TenantPlan.ENTERPRISE, TenantPlan.OPEN_SOURCE -> null to null
        }

    companion object {
        // Defaults from issue #273's spec — set explicitly at provisioning time rather than
        // left null to await #273 (not yet built), since these are meant to be per-tenant
        // data, not a filter-side hardcoded table.
        private const val CLOUD_RATE_LIMIT_PER_SECOND = 100
        private const val CLOUD_RATE_LIMIT_PER_MINUTE = 1000
    }
}
