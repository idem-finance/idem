package finance.idem.application.ledger

import finance.idem.core.EntryType
import finance.idem.core.MonetaryAmount
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.TransactionRepository
import java.time.Instant

class QueryBalanceUseCase(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
) {
    fun execute(query: QueryBalanceQuery): Result<Balance> {
        val account = accountRepository.findById(query.accountId, query.tenantId)
            ?: return Result.failure(QueryBalanceError.AccountNotFound(query.accountId))

        val transactions = transactionRepository
            .findByAccountId(query.accountId, query.tenantId)
            .let { txs ->
                val cutoff = query.asOf
                if (cutoff != null) txs.filter { it.occurredAt <= cutoff } else txs
            }

        var debits = MonetaryAmount.ZERO
        var credits = MonetaryAmount.ZERO

        for (tx in transactions) {
            for (line in tx.lines) {
                if (line.accountId != query.accountId) continue
                when (line.entryType) {
                    EntryType.DEBIT  -> debits  = debits  + line.monetaryEntry.amount
                    EntryType.CREDIT -> credits = credits + line.monetaryEntry.amount
                }
            }
        }

        val net = when (account.normalBalance) {
            EntryType.DEBIT  -> debits - credits
            EntryType.CREDIT -> credits - debits
        }

        return Result.success(
            Balance(
                accountId = account.id,
                currency = account.currency,
                amount = net,
                normalBalance = account.normalBalance,
                computedAt = Instant.now(),
            )
        )
    }
}
