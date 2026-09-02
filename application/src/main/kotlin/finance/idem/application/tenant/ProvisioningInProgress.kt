package finance.idem.application.tenant

/** A concurrent request already claimed this Idempotency-Key and hasn't finished yet. */
class ProvisioningInProgress(
    message: String,
) : Exception(message)
