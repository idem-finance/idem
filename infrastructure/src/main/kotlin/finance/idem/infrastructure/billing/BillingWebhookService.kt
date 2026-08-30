package finance.idem.infrastructure.billing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import finance.idem.application.billing.BillingWebhookUseCase
import finance.idem.core.TenantId
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.infrastructure.security.HmacSigner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class BillingWebhookService(
    private val tenantConfigRepository: TenantConfigRepository,
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

        runCatching {
            tenantConfigRepository.invalidate(TenantId.of(payload.tenantId))
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
