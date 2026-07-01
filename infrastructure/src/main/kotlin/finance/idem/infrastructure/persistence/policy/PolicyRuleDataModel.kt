package finance.idem.infrastructure.persistence.policy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "policy_rules")
class PolicyRuleDataModel(
    @Id
    val id: UUID,
    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,
    @Column(name = "agent_key_prefix")
    val agentKeyPrefix: String?,
    @Column(name = "rule_type", nullable = false)
    val ruleType: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb", nullable = false)
    val params: String,
    @Column(nullable = false)
    val enabled: Boolean,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
) {
    protected constructor() : this(
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        "",
        "{}",
        true,
        Instant.now(),
        Instant.now(),
    )
}
