package finance.idem.infrastructure.persistence.reconciliation

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class SettlementRepositoryAdapter(
    private val jpaRepository: SettlementJpaRepository,
    private val entityManager: EntityManager,
) : SettlementRepository {

    @Transactional
    override fun save(settlement: Settlement): Settlement {
        entityManager.setRlsTenantId(settlement.tenantId)
        return jpaRepository.save(settlement.toEntity()).toDomain()
    }

    @Transactional(readOnly = true)
    override fun findById(id: UUID, tenantId: TenantId): Settlement? {
        entityManager.setRlsTenantId(tenantId)
        return jpaRepository.findById(id).orElse(null)?.toDomain()
    }

    // Not readOnly: PESSIMISTIC_WRITE (SELECT ... FOR UPDATE) acquires row locks,
    // which a read-only transaction cannot do.
    @Transactional
    override fun findPendingCandidates(
        tenantId: TenantId,
        accountIds: Set<AccountId>,
        token: StablecoinToken,
        chainId: ChainId,
        walletAddress: String,
        since: Instant,
    ): List<Settlement> {
        entityManager.setRlsTenantId(tenantId)
        return jpaRepository.findPendingCandidates(
            tenantId.value, accountIds.map { it.value }.toSet(),
            token.name, chainId.name, walletAddress, since,
        ).map { it.toDomain() }
    }

    // Not readOnly: PESSIMISTIC_WRITE on findUnmatchedInWindow requires a read-write transaction.
    @Transactional
    override fun findUnmatchedInWindow(
        tenantId: TenantId,
        accountId: AccountId?,
        from: Instant,
        to: Instant,
    ): List<Settlement> {
        entityManager.setRlsTenantId(tenantId)
        return jpaRepository.findUnmatchedInWindow(
            tenantId.value, accountId?.value, from, to,
        ).map { it.toDomain() }
    }

    @Transactional(readOnly = true)
    override fun findPage(
        tenantId: TenantId,
        status: EntryStatus?,
        from: Instant?,
        to: Instant?,
        afterCreatedAt: Instant?,
        afterId: UUID?,
        limit: Int,
    ): List<Settlement> {
        entityManager.setRlsTenantId(tenantId)
        return jpaRepository.findPage(
            tenantId.value, status?.name, from, to, afterCreatedAt, afterId, limit,
        ).map { it.toDomain() }
    }
}

private fun Settlement.toEntity() = SettlementDataModel(
    id = id, tenantId = tenantId.value, accountId = accountId.value,
    amount = amount.value, token = token.name, chainId = chainId.name,
    walletAddress = walletAddress, status = status.name,
    matchedTransactionId = matchedTransactionId?.value,
    txHash = txHash, blockNumber = blockNumber, confirmedAt = confirmedAt,
    expectedFromAddress = expectedFromAddress,
    createdAt = createdAt, createdBy = createdBy,
)

private fun SettlementDataModel.toDomain() = Settlement(
    id = id, tenantId = TenantId(tenantId), accountId = AccountId(accountId),
    amount = MonetaryAmount.of(amount), token = StablecoinToken.valueOf(token),
    chainId = ChainId.valueOf(chainId), walletAddress = walletAddress,
    status = EntryStatus.valueOf(status),
    matchedTransactionId = matchedTransactionId?.let { TransactionId(it) },
    txHash = txHash, blockNumber = blockNumber, confirmedAt = confirmedAt,
    expectedFromAddress = expectedFromAddress,
    createdAt = createdAt, createdBy = createdBy,
)
