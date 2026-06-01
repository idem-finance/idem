package finance.idem.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "journal_lines")
class JournalLineJpaEntity(
    @Id
    val id: UUID,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    val transaction: TransactionJpaEntity,

    @Column(name = "account_id", nullable = false)
    val accountId: UUID,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(name = "entry_type", nullable = false)
    val entryType: String,

    @Column(nullable = false, precision = 38, scale = 18)
    val amount: BigDecimal,

    @Column(nullable = false)
    val currency: String,

    @Column(name = "monetary_entry_type", nullable = false)
    val monetaryEntryType: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "monetary_entry_data", columnDefinition = "jsonb", nullable = false)
    val monetaryEntryData: String,

    val description: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "created_by", nullable = false)
    val createdBy: String,
) {
    protected constructor() : this(
        UUID.randomUUID(), TransactionJpaEntity(), UUID.randomUUID(), UUID.randomUUID(),
        "", BigDecimal.ZERO, "", "", "{}", null, Instant.now(), "",
    )
}
