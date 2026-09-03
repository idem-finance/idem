package finance.idem.infrastructure.service

import finance.idem.application.port.AdminTokenAuthenticator
import finance.idem.application.port.EmailSender
import finance.idem.application.port.TenantProvisioningIdempotencyStore
import finance.idem.application.port.TenantRepository
import finance.idem.application.tenant.InvalidAdminToken
import finance.idem.application.tenant.ProvisionTenantCommand
import finance.idem.application.tenant.ProvisionTenantUseCase
import finance.idem.application.tenant.ProvisionedTenant
import finance.idem.application.tenant.ProvisioningInProgress
import finance.idem.application.tenant.SuspendTenantUseCase
import finance.idem.application.tenant.TenantNotFound
import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope
import finance.idem.core.tenant.Tenant
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.core.tenant.TenantPlan
import finance.idem.infrastructure.security.ApiKeyService
import finance.idem.infrastructure.tenant.DashboardProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Service
class TenantProvisioningService(
    private val tenantRepository: TenantRepository,
    private val tenantConfigRepository: TenantConfigRepository,
    private val apiKeyService: ApiKeyService,
    private val emailSender: EmailSender,
    private val idempotencyStore: TenantProvisioningIdempotencyStore,
    private val adminTokenAuthenticator: AdminTokenAuthenticator,
    private val dashboardProperties: DashboardProperties,
    txManager: PlatformTransactionManager,
) : ProvisionTenantUseCase,
    SuspendTenantUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    // Scoped to only the DB-write portion of provisioning — see execute() below. Keeping
    // the synchronous Resend call inside a method-level @Transactional held the DB
    // connection open for the whole HTTP round trip, risking pool exhaustion under a
    // signup burst or a slow/down Resend. Same split pattern as ReconcileEntriesService.
    private val transactionTemplate = TransactionTemplate(txManager)

    override fun execute(cmd: ProvisionTenantCommand): Result<ProvisionedTenant> {
        if (!adminTokenAuthenticator.isValid(cmd.adminToken)) {
            return Result.failure(InvalidAdminToken("Missing or invalid internal admin token"))
        }

        if (!idempotencyStore.claim(cmd.idempotencyKey)) {
            val cached =
                idempotencyStore.findCached(cmd.idempotencyKey)
                    ?: return Result.failure(ProvisioningInProgress("Provisioning already in progress for this Idempotency-Key"))
            return Result.success(cached)
        }

        val provisioned =
            runCatching {
                transactionTemplate.execute { provisionRecords(cmd) }
                    ?: error("Provisioning transaction rolled back with no exception")
            }.onFailure {
                idempotencyStore.release(cmd.idempotencyKey)
            }.getOrElse { return Result.failure(it) }

        idempotencyStore.cache(cmd.idempotencyKey, provisioned)

        runCatching {
            emailSender.sendWelcomeEmail(cmd.contactEmail, cmd.organizationName, provisioned.rawApiKey, provisioned.dashboardUrl)
        }.onFailure { e ->
            log.warn("Welcome email failed for tenant {} — provisioning still succeeds", provisioned.tenantId.value, e)
        }

        return Result.success(provisioned)
    }

    private fun provisionRecords(cmd: ProvisionTenantCommand): ProvisionedTenant {
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

        tenantConfigRepository.upsert(
            TenantConfig(
                tenantId = tenantId,
                plan = TenantPlan.CLOUD,
                rateLimitPerSecond = CLOUD_RATE_LIMIT_PER_SECOND,
                rateLimitPerMinute = CLOUD_RATE_LIMIT_PER_MINUTE,
                featureFlags = emptySet(),
                hmacKey = null,
                billingCustomerId = null,
                createdAt = now,
                suspendedAt = null,
            ),
        )

        val (rawKey, _) = apiKeyService.generate(tenantId, DEFAULT_TENANT_SCOPES)
        val dashboardUrl = "${dashboardProperties.baseUrl}/t/${tenantId.value}"
        return ProvisionedTenant(tenantId, rawKey, dashboardUrl)
    }

    @Transactional
    override fun execute(
        adminToken: String?,
        tenantId: TenantId,
    ): Result<Instant> {
        if (!adminTokenAuthenticator.isValid(adminToken)) {
            return Result.failure(InvalidAdminToken("Missing or invalid internal admin token"))
        }

        val existing =
            tenantConfigRepository.findByTenantId(tenantId)
                ?: return Result.failure(TenantNotFound("No tenant found for id=${tenantId.value}"))

        val suspendedAt = Instant.now()
        tenantConfigRepository.upsert(existing.copy(suspendedAt = suspendedAt))
        return Result.success(suspendedAt)
    }

    companion object {
        // This endpoint provisions Cloud tenants only — Enterprise runs as a separate
        // deployment (IdemEnterpriseApplication, private repo, customer VPC via Terraform
        // per CLAUDE.md) and Open Source is self-hosted; neither ever calls this endpoint.
        // Defaults from issue #273's spec.
        private const val CLOUD_RATE_LIMIT_PER_SECOND = 100
        private const val CLOUD_RATE_LIMIT_PER_MINUTE = 1000

        // Excludes ADMIN (instance-wide /actuator/** access) and AGENTS_ROLLBACK (CLAUDE.md:
        // "grant only to ADMIN-tier keys or dedicated rollback agent keys") — a brand-new
        // signup is neither. Granting either becomes an explicit follow-up key-management
        // action, not an automatic side effect of provisioning.
        private val DEFAULT_TENANT_SCOPES = ApiScope.entries.toSet() - setOf(ApiScope.ADMIN, ApiScope.AGENTS_ROLLBACK)
    }
}
