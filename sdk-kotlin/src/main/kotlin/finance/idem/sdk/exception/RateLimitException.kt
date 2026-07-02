package finance.idem.sdk.exception

class RateLimitException(
    val retryAfterSeconds: Int,
    traceId: String? = null,
) : IdemException("Rate limit exceeded; retry after $retryAfterSeconds seconds", traceId = traceId)
