package finance.idem.infrastructure.billing

// The billing system's payload only needs to name which tenant changed — it signals
// "re-read this tenant's config from the source of truth," it does not carry the new
// values itself (those are written via the tenant provisioning path, see #272).
data class BillingWebhookPayload(
    val tenantId: String,
)
