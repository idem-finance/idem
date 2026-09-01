package finance.idem.infrastructure.persistence.tenant

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tenants")
class TenantDataModel(
    @Id
    val id: UUID,
    @Column(name = "webhook_url")
    val webhookUrl: String?,
    @Column(name = "webhook_secret")
    val webhookSecret: String?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
    @Column(name = "plan", nullable = false)
    val plan: String = "OPEN_SOURCE",
    @Column(name = "rate_limit_per_second")
    val rateLimitPerSecond: Int? = null,
    @Column(name = "rate_limit_per_minute")
    val rateLimitPerMinute: Int? = null,
    @Column(name = "feature_flags", nullable = false)
    val featureFlags: String = "",
    @Column(name = "hmac_key")
    val hmacKey: String? = null,
    @Column(name = "billing_customer_id")
    val billingCustomerId: String? = null,
    @Column(name = "suspended_at")
    val suspendedAt: Instant? = null,
) {
    protected constructor() : this(UUID.randomUUID(), null, null, Instant.now(), Instant.now())
}
