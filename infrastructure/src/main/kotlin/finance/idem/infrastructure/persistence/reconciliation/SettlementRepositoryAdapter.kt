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

    private fun setTenantId(tenantId: TenantId) {
        // UUID contains only hex digits and dashes — safe to interpolate without binding
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantId.value}'").executeUpdate()
    }

    @Transactional
    override fun save(settlement: Settlement): Settlement {
        setTenantId(settlement.tenantId)
        return jpaRepository.save(settlement.toEntity()).toDomain()
    }

    @Transactional(readOnly = true)
    override fun findById(id: UUID, tenantId: TenantId): Settlement? {
        setTenantId(tenantId)
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
        setTenantId(tenantId)
        return jpaRepository.findPendingCandidates(
            tenantId.value, accountIds.map { it.value }.toSet(),
            token.name, chainId.name, walletAddress, since,
        ).map { it.toDomain() }
    }

    @Transactional(readOnly = true)
    override fun findUnmatchedInWindow(
        tenantId: TenantId,
        accountId: AccountId?,
        from: Instant,
        to: Instant,
    ): List<Settlement> {
        setTenantId(tenantId)
        return jpaRepository.findUnmatchedInWindow(
            tenantId.value, accountId?.value, from, to,
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
