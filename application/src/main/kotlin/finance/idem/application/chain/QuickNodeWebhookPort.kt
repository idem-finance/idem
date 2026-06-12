package finance.idem.application.chain

fun interface QuickNodeWebhookPort {
    /**
     * Handle an inbound QuickNode Streams webhook.
     *
     * [signature] is `X-QN-Signature`; [nonce] and [timestamp] are `X-QN-Nonce` and
     * `X-QN-Timestamp` respectively. QuickNode signs `nonce + timestamp + rawBody`, so all
     * three are required together for HMAC validation.
     *
     * Returns [Result.failure] only on authentication rejection — the caller maps this to 401.
     * All processing errors are handled internally and logged; the result is still [Result.success].
     */
    fun handle(signature: String?, nonce: String?, timestamp: String?, rawBody: String): Result<Unit>
}
