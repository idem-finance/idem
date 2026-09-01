package finance.idem.application.tenant

/** The internal admin token on this request is missing or does not match the configured value. */
class InvalidAdminToken(
    message: String,
) : Exception(message)
