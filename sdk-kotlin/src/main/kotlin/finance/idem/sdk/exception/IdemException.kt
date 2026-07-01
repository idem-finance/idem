package finance.idem.sdk.exception

sealed class IdemException(
    message: String,
    cause: Throwable? = null,
    val traceId: String? = null,
) : Exception(message, cause)
