package finance.idem.sdk.exception

class NetworkException(
    override val cause: Throwable,
) : IdemException("Network error: ${cause.message}", cause)
