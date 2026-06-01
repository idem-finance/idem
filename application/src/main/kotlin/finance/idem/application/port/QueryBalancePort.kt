package finance.idem.application.port

import finance.idem.application.ledger.Balance
import finance.idem.application.ledger.QueryBalanceQuery

/**
 * Inbound port for querying account balance.
 * Implemented by QueryBalanceService in infrastructure, which adds @Transactional boundary.
 */
interface QueryBalancePort {
    fun execute(query: QueryBalanceQuery): Result<Balance>
}
