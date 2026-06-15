package finance.idem.sdk.exception

class ApiException(
    val statusCode: Int,
    val errorCode: String,
    override val message: String,
    traceId: String? = null,
) : IdemException(message, traceId = traceId)