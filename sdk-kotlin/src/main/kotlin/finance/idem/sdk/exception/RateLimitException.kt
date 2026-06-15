package finance.idem.sdk.exception

class RateLimitException(
    val retryAfterSeconds: Int,
) : IdemException("Rate limit exceeded; retry after $retryAfterSeconds seconds")