package finance.idem.infrastructure.persistence.chain

import finance.idem.core.chain.FailedChainTransfer
import finance.idem.core.chain.FailedChainTransferRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class FailedChainTransferRepositoryAdapter(
    private val jpaRepository: FailedChainTransferJpaRepository,
) : FailedChainTransferRepository {

    @Transactional
    override fun save(transfer: FailedChainTransfer) {
        jpaRepository.upsert(
            id = transfer.id.toString(),
            chainKey = transfer.chainKey,
            source = transfer.source,
            idempotencyKey = transfer.idempotencyKey,
            txHash = transfer.txHash,
            blockNumber = transfer.blockNumber,
            tenantId = transfer.tenantId.value.toString(),
            walletAddress = transfer.walletAddress,
            tokenContract = transfer.tokenContract,
            debitAccountId = transfer.debitAccountId.toString(),
            creditAccountId = transfer.creditAccountId.toString(),
            token = transfer.token.name,
            amount = transfer.amount.value,
            errorMessage = transfer.errorMessage,
            createdAt = transfer.createdAt,
        )
    }
}
