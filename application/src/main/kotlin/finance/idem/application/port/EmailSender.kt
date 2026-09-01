package finance.idem.application.port

interface EmailSender {
    fun sendWelcomeEmail(
        to: String,
        organizationName: String,
        rawApiKey: String,
        dashboardUrl: String,
    )
}
