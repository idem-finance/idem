package finance.idem.infrastructure.service

import finance.idem.application.port.TenantRepository
import finance.idem.application.tenant.GetWebhookConfigUseCase
import finance.idem.application.tenant.TenantWebhookConfig
import finance.idem.core.TenantId
import org.springframework.stereotype.Service

@Service
class GetWebhookConfigService(
    private val tenantRepository: TenantRepository,
) : GetWebhookConfigUseCase {
    override fun execute(tenantId: TenantId): TenantWebhookConfig? = tenantRepository.findWebhookConfig(tenantId)
}
