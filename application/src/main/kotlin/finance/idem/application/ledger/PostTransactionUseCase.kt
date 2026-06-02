package finance.idem.application.ledger

import finance.idem.core.TransactionId

interface PostTransactionUseCase {
    fun execute(cmd: PostTransactionCommand): Result<TransactionId>
}
