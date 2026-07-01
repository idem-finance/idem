package finance.idem.infrastructure.service

import finance.idem.application.settlement.GetSettlementQuery
import finance.idem.application.settlement.GetSettlementUseCase
import finance.idem.application.settlement.SettlementNotFound
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GetSettlementService(
    private val settlementRepository: SettlementRepository,
) : GetSettlementUseCase {

    @Transactional(readOnly = true)
    override fun execute(query: GetSettlementQuery): Result<Settlement> {
        val settlement = settlementRepository.findById(query.id, query.tenantId)
            ?: return Result.failure(SettlementNotFound(query.id))
        return Result.success(settlement)
    }
}
