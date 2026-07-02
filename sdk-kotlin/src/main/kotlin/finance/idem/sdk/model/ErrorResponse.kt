package finance.idem.sdk.model

/** Wire shape for non-2xx error bodies; mapped to [finance.idem.sdk.exception.ApiException] by [finance.idem.sdk.IdemClient]. */
internal data class ErrorResponse(
    val code: String,
    val message: String,
)
