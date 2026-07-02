package finance.idem.infrastructure.security

import finance.idem.application.security.GenerateApiKeyCommand
import finance.idem.application.security.GenerateApiKeyUseCase
import finance.idem.application.security.GeneratedApiKey
import finance.idem.application.security.InsufficientCallerScope
import finance.idem.application.security.ListApiKeysUseCase
import finance.idem.application.security.RevokeApiKeyUseCase
import finance.idem.core.TenantId
import finance.idem.core.security.ApiKey
import finance.idem.core.security.ApiKeyId
import finance.idem.core.security.ApiKeyRepository
import org.springframework.stereotype.Service

@Service
class ApiKeyManagementService(
    private val apiKeyService: ApiKeyService,
    private val apiKeyRepository: ApiKeyRepository,
) : GenerateApiKeyUseCase,
    ListApiKeysUseCase,
    RevokeApiKeyUseCase {
    override fun execute(cmd: GenerateApiKeyCommand): Result<GeneratedApiKey> {
        if (!cmd.callerScopes.containsAll(cmd.requestedScopes)) {
            val excess = cmd.requestedScopes - cmd.callerScopes
            return Result.failure(
                InsufficientCallerScope("Requested scopes exceed caller's own scopes: $excess"),
            )
        }
        val (rawKey, apiKey) = apiKeyService.generate(cmd.tenantId, cmd.requestedScopes)
        return Result.success(GeneratedApiKey(rawKey, apiKey))
    }

    override fun execute(tenantId: TenantId): List<ApiKey> = apiKeyRepository.findAllByTenantId(tenantId)

    override fun execute(
        keyId: ApiKeyId,
        tenantId: TenantId,
    ): Boolean = apiKeyService.revoke(keyId, tenantId)
}
