package finance.idem.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.ledger.TransactionStatus
import finance.idem.core.WorkflowPlanId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class TransactionRepositoryAdapter(
    private val jpaRepository: TransactionJpaRepository,
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
) : TransactionRepository {

    private fun setTenantId(tenantId: TenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.tenant_id', :tid, true)")
            .setParameter("tid", tenantId.value.toString())
            .singleResult
    }

    @Transactional(readOnly = true)
    override fun findById(id: TransactionId, tenantId: TenantId): Transaction? {
        setTenantId(tenantId)
        return jpaRepository.findByIdAndTenantId(id.value, tenantId.value)?.toDomain(objectMapper)
    }

    @Transactional
    override fun save(transaction: Transaction): Transaction {
        setTenantId(transaction.tenantId)
        val entity = transaction.toEntity(objectMapper)
        jpaRepository.save(entity)
        return transaction
    }

    @Transactional(readOnly = true)
    override fun findByIdempotencyKey(key: String, tenantId: TenantId): Transaction? {
        setTenantId(tenantId)
        return jpaRepository.findByIdempotencyKeyAndTenantId(key, tenantId.value)?.toDomain(objectMapper)
    }

    @Transactional(readOnly = true)
    override fun findByAccountId(accountId: AccountId, tenantId: TenantId): List<Transaction> {
        setTenantId(tenantId)
        return jpaRepository.findByAccountIdAndTenantId(accountId.value, tenantId.value)
            .map { it.toDomain(objectMapper) }
    }
}

// ── Entity → Domain ────────────────────────────────────────────────────────────

private fun TransactionJpaEntity.toDomain(mapper: ObjectMapper): Transaction {
    val txId = TransactionId(id)
    val tenantId = TenantId(tenantId)
    return Transaction.reconstitute(
        id = txId,
        tenantId = tenantId,
        idempotencyKey = idempotencyKey,
        lines = lines.map { it.toDomain(txId, tenantId, mapper) },
        status = TransactionStatus.valueOf(status),
        agentContext = agentContext?.let { mapper.readAgentContext(it) },
        metadata = mapper.readValue<Map<String, String>>(metadata),
        occurredAt = occurredAt,
        createdAt = createdAt,
        createdBy = createdBy,
    )
}

private fun JournalLineJpaEntity.toDomain(
    txId: TransactionId,
    tenantId: TenantId,
    mapper: ObjectMapper,
): JournalLine {
    val cols = MonetaryEntryColumns(
        amount = amount,
        currency = currency,
        monetaryEntryType = monetaryEntryType,
        monetaryEntryData = monetaryEntryData,
    )
    return JournalLine(
        id = id,
        transactionId = txId,
        accountId = AccountId(accountId),
        tenantId = tenantId,
        entryType = EntryType.valueOf(entryType),
        monetaryEntry = cols.toDomain(mapper),
        description = description,
        createdAt = createdAt,
        createdBy = createdBy,
    )
}

private fun ObjectMapper.readAgentContext(json: String): AgentContext {
    val map: Map<String, String?> = readValue(json)
    return AgentContext(
        agentId = map["agentId"]!!,
        sessionId = map["sessionId"]!!,
        workflowPlanId = map["workflowPlanId"]?.let { WorkflowPlanId(UUID.fromString(it)) },
        intent = map["intent"],
    )
}

// ── Domain → Entity ────────────────────────────────────────────────────────────

private fun Transaction.toEntity(mapper: ObjectMapper): TransactionJpaEntity {
    val entity = TransactionJpaEntity(
        id = id.value,
        tenantId = tenantId.value,
        idempotencyKey = idempotencyKey,
        status = status.name,
        agentContext = agentContext?.let {
            mapper.writeValueAsString(mapOf(
                "agentId" to it.agentId,
                "sessionId" to it.sessionId,
                "workflowPlanId" to it.workflowPlanId?.value?.toString(),
                "intent" to it.intent,
            ))
        },
        metadata = mapper.writeValueAsString(metadata),
        occurredAt = occurredAt,
        createdAt = createdAt,
        createdBy = createdBy,
    )
    lines.map { it.toEntity(entity, mapper) }.forEach { entity.lines.add(it) }
    return entity
}

private fun JournalLine.toEntity(transaction: TransactionJpaEntity, mapper: ObjectMapper): JournalLineJpaEntity {
    val cols = monetaryEntry.toColumns(mapper)
    return JournalLineJpaEntity(
        id = id,
        transaction = transaction,
        accountId = accountId.value,
        tenantId = tenantId.value,
        entryType = entryType.name,
        amount = cols.amount,
        currency = cols.currency,
        monetaryEntryType = cols.monetaryEntryType,
        monetaryEntryData = cols.monetaryEntryData,
        description = description,
        createdAt = createdAt,
        createdBy = createdBy,
    )
}
