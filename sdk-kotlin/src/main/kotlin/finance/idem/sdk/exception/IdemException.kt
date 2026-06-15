package finance.idem.sdk.exception

sealed class IdemException(message: String, cause: Throwable? = null) : Exception(message, cause)