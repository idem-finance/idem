package finance.idem.infrastructure.service

import finance.idem.application.port.SettlementIdempotencyStore
import finance.idem.application.settlement.AccountNotFoundForSettlement
import finance.idem.application.settlement.RegisterSettlementCommand
import finance.idem.application.settlement.RegisterSettlementUseCase
import finance.idem.application.settlement.SettlementIdempotencyConflict
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class RegisterSettlementService(
    private val accountRepository: AccountRepository,
    private val settlementRepository: SettlementRepository,
    private val settlementIdempotencyStore: SettlementIdempotencyStore,
) : RegisterSettlementUseCase {
    @Transactional
    override fun execute(cmd: RegisterSettlementCommand): Result<Settlement> {
        val settlementId = UUID.randomUUID()

        if (!settlementIdempotencyStore.tryRecord(cmd.idempotencyKey, cmd.tenantId, settlementId)) {
            val existingId =
                settlementIdempotencyStore.find(cmd.idempotencyKey, cmd.tenantId)
                    ?: return Result.failure(SettlementIdempotencyConflict(cmd.idempotencyKey))
            val existing =
                settlementRepository.findById(existingId, cmd.tenantId)
                    ?: return Result.failure(SettlementIdempotencyConflict(cmd.idempotencyKey))
            return Result.success(existing)
        }

        if (!accountRepository.existsById(cmd.accountId, cmd.tenantId)) {
            return Result.failure(AccountNotFoundForSettlement(cmd.accountId))
        }
        val settlement =
            Settlement(
                id = settlementId,
                tenantId = cmd.tenantId,
                accountId = cmd.accountId,
                amount = cmd.amount,
                token = cmd.token,
                chainId = cmd.chainId,
                walletAddress = cmd.walletAddress,
                status = EntryStatus.PENDING,
                expectedFromAddress = cmd.expectedFromAddress,
                createdAt = Instant.now(),
                createdBy = cmd.createdBy,
            )
        return Result.success(settlementRepository.save(settlement))
    }
}
