package finance.idem.application.settlement

import finance.idem.core.ledger.Settlement

interface RegisterSettlementUseCase {
    fun execute(cmd: RegisterSettlementCommand): Result<Settlement>
}
