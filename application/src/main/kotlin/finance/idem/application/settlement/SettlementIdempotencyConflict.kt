package finance.idem.application.settlement

class SettlementIdempotencyConflict(
    val key: String,
) : Exception("Idempotency conflict — a request with key '$key' is already in progress")
