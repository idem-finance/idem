package finance.idem.application.port

/** Validates the internal admin token that gates the tenant-provisioning admin API (#272). */
interface AdminTokenAuthenticator {
    fun isValid(provided: String?): Boolean
}
