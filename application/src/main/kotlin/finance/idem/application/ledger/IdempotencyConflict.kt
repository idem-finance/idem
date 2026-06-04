package finance.idem.application.ledger

class IdempotencyConflict(val key: String) :
    PostTransactionError("Idempotency conflict — a request with key '$key' is already in progress")
