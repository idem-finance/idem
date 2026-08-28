package finance.idem.application.billing

interface BillingWebhookUseCase {
    fun handle(
        signature: String?,
        rawBody: String,
    ): Result<Unit>
}
