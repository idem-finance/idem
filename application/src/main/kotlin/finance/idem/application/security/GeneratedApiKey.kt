package finance.idem.application.security

import finance.idem.core.security.ApiKey

data class GeneratedApiKey(
    val rawKey: String,
    val apiKey: ApiKey,
)
