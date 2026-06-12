package finance.idem.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.JournalLineRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class JournalLineRepositoryAdapter(
    private val jpaRepository: JournalLineJpaRepository,
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
) : JournalLineRepository {

    private fun setTenantId(tenantId: TenantId) {
        // UUID contains only hex digits and dashes — safe to interpolate without binding
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantId.value}'")
            .executeUpdate()
    }

    @Transactional(readOnly = true)
    override fun findByAccountId(
        accountId: AccountId,
        tenantId: TenantId,
        from: Instant?,
        to: Instant?,
        afterCreatedAt: Instant?,
        afterId: UUID?,
        limit: Int,
    ): List<JournalLine> {
        setTenantId(tenantId)
        return jpaRepository.findPage(
            accountId = accountId.value,
            tenantId = tenantId.value,
            from = from,
            to = to,
            afterCreatedAt = afterCreatedAt,
            afterId = afterId,
            limit = limit,
        ).map { it.toDomain(tenantId, objectMapper) }
    }
}

private fun JournalLineDataModel.toDomain(tenantId: TenantId, mapper: ObjectMapper): JournalLine {
    val cols = MonetaryEntryColumns(
        amount = amount,
        currency = currency,
        monetaryEntryType = monetaryEntryType,
        monetaryEntryData = monetaryEntryData,
    )
    return JournalLine(
        id = id,
        transactionId = TransactionId(transaction.id),
        accountId = AccountId(accountId),
        tenantId = tenantId,
        entryType = EntryType.valueOf(entryType),
        monetaryEntry = cols.toDomain(mapper),
        description = description,
        createdAt = createdAt,
        createdBy = createdBy,
    )
}
