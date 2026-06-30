package finance.idem.application.settlement

import finance.idem.core.ledger.Settlement

interface GetSettlementUseCase {
    fun execute(query: GetSettlementQuery): Result<Settlement>
}
