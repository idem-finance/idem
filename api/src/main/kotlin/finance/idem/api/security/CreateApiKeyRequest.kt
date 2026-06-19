package finance.idem.api.security

import finance.idem.core.security.ApiScope
import jakarta.validation.constraints.NotEmpty

data class CreateApiKeyRequest(
    @field:NotEmpty(message = "scopes must not be empty")
    val scopes: Set<ApiScope> = emptySet(),
)
