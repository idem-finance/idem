package finance.idem.infrastructure.outbox

fun interface WebhookUrlValidator {
    fun validate(rawUrl: String): Result<Unit>
}
