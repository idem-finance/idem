package finance.idem.application.chain

fun interface AlchemyWebhookPort {
    /**
     * Handle an inbound Alchemy webhook request.
     * Returns [Result.failure] only on authentication rejection — the caller maps this to 401.
     * All processing errors are handled internally and logged; the result is still [Result.success].
     */
    fun handle(signature: String?, rawBody: String): Result<Unit>
}
