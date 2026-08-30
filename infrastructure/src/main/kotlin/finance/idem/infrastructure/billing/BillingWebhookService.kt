package finance.idem.infrastructure.billing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import finance.idem.application.billing.BillingWebhookUseCase
import finance.idem.core.TenantId
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.infrastructure.persistence.tenant.TenantJpaRepository
import finance.idem.infrastructure.security.HmacSigner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The HMAC signature over the request body proves the caller holds the shared billing
 * secret — i.e. that the request came from the trusted billing integration — but says
 * nothing about whether the tenantId named inside that body is legitimate: the secret is
 * global, not per-tenant, and the tenantId is inherent to the payload it signs. That trust
 * model is acceptable ONLY because this endpoint's sole effect is idempotent cache
 * invalidation; do not extend this pattern to a mutating endpoint without per-tenant auth.
 */
@Service
class BillingWebhookService(
    private val tenantConfigRepository: TenantConfigRepository,
    private val tenantJpaRepository: TenantJpaRepository,
    private val objectMapper: ObjectMapper,
    private val config: BillingConfig,
) : BillingWebhookUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(
        signature: String?,
        rawBody: String,
    ): Result<Unit> {
        val secret = config.webhookSecret
        if (secret.isBlank()) {
            log.warn("Billing webhook received but idem.billing.webhook-secret is not configured — rejecting")
            return Result.failure(IllegalStateException("Billing webhook not configured"))
        }
        if (signature == null || !isValidSignature(secret, rawBody, signature)) {
            log.warn("Billing webhook rejected — invalid or missing X-Idem-Signature")
            return Result.failure(IllegalArgumentException("Invalid or missing X-Idem-Signature"))
        }

        val payload =
            runCatching {
                objectMapper.readValue<BillingWebhookPayload>(rawBody)
            }.getOrElse {
                log.warn("Billing webhook: failed to parse payload: ${it.message}")
                return Result.success(Unit)
            }

        runCatching { TenantId.of(payload.tenantId) }
            .onSuccess { tenantId ->
                if (tenantJpaRepository.existsById(tenantId.value)) {
                    tenantConfigRepository.invalidate(tenantId)
                } else {
                    log.warn("Billing webhook: unknown tenantId '${payload.tenantId}' — ignoring")
                }
            }.onFailure {
                log.warn("Billing webhook: invalid tenantId '${payload.tenantId}' — ignoring")
            }

        return Result.success(Unit)
    }

    companion object {
        internal fun isValidSignature(
            secret: String,
            rawBody: String,
            signature: String,
        ): Boolean = HmacSigner.verify(secret, rawBody, signature)
    }
}
