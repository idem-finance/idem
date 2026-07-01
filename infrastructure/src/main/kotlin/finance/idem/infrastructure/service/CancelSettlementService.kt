package finance.idem.infrastructure.service

import finance.idem.application.settlement.CancelSettlementCommand
import finance.idem.application.settlement.CancelSettlementUseCase
import finance.idem.application.settlement.SettlementAlreadyTerminal
import finance.idem.application.settlement.SettlementNotFound
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CancelSettlementService(
    private val settlementRepository: SettlementRepository,
) : CancelSettlementUseCase {
    @Transactional
    override fun execute(cmd: CancelSettlementCommand): Result<Settlement> {
        val settlement =
            settlementRepository.findById(cmd.id, cmd.tenantId)
                ?: return Result.failure(SettlementNotFound(cmd.id))

        if (settlement.status != EntryStatus.PENDING) {
            return Result.failure(SettlementAlreadyTerminal(settlement.status))
        }

        return Result.success(settlementRepository.save(settlement.copy(status = EntryStatus.CANCELLED)))
    }
}
