package finance.idem.application.port

import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.core.TransactionId

/**
 * Inbound port for posting a transaction.
 * Implemented by PostTransactionService in infrastructure, which adds @Transactional boundary.
 */
interface PostTransactionPort {
    fun execute(cmd: PostTransactionCommand): Result<TransactionId>
}
