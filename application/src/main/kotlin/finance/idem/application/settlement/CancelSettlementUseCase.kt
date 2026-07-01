package finance.idem.application.settlement

import finance.idem.core.ledger.Settlement

interface CancelSettlementUseCase {
    fun execute(cmd: CancelSettlementCommand): Result<Settlement>
}
