package finance.idem.application.tenant

/** No tenant row exists for the given tenant id. */
class TenantNotFound(
    message: String,
) : Exception(message)
