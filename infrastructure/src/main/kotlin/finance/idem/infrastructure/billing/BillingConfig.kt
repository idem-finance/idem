package finance.idem.infrastructure.billing

import org.springframework.boot.context.properties.ConfigurationProperties

// Deliberately no @NotBlank / fail-fast validator like ChainWebhookSecurityValidator: unlike
// Alchemy/QuickNode (required by every production deployment), the billing webhook is
// Cloud-only infra self-hosted installs never configure. Blank secret means the receiver
// stays reachable but rejects every request (see BillingWebhookService) rather than the
// dev-mode "warn and skip validation" posture chain webhooks use — this endpoint can trigger
// tenant-config cache invalidation, so fail-closed by default instead of fail-open.
@ConfigurationProperties("idem.billing")
data class BillingConfig(
    val webhookSecret: String = "",
)
