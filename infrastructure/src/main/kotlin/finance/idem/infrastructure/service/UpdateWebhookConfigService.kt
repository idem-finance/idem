package finance.idem.infrastructure.service

import finance.idem.application.port.TenantRepository
import finance.idem.application.tenant.TenantWebhookConfig
import finance.idem.application.tenant.UpdateWebhookConfigUseCase
import finance.idem.core.TenantId
import finance.idem.infrastructure.outbox.WebhookUrlValidator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom

@Service
class UpdateWebhookConfigService(
    private val tenantRepository: TenantRepository,
    private val webhookUrlValidator: WebhookUrlValidator,
) : UpdateWebhookConfigUseCase {
    @Transactional
    override fun execute(
        tenantId: TenantId,
        webhookUrl: String,
    ): Result<TenantWebhookConfig> {
        webhookUrlValidator.validate(webhookUrl).onFailure { return Result.failure(it) }

        val secret = generateSecret()
        val config = TenantWebhookConfig(webhookUrl = webhookUrl, webhookSecret = secret)
        tenantRepository.upsertWebhookConfig(tenantId, config)
        return Result.success(config)
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
