package finance.idem.infrastructure.service

import finance.idem.application.ledger.Balance
import finance.idem.application.ledger.BalanceAccountNotFound
import finance.idem.application.ledger.QueryBalanceError
import finance.idem.application.ledger.QueryBalanceQuery
import finance.idem.application.ledger.QueryBalanceUseCase
import finance.idem.core.EntryType
import finance.idem.core.MonetaryAmount
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.monetary.FiatEntry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
@Transactional(readOnly = true)
class QueryBalanceService(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val clock: Clock = Clock.systemUTC(),
) : QueryBalanceUseCase {

    override fun execute(query: QueryBalanceQuery): Result<Balance> {
        val account = accountRepository.findById(query.accountId, query.tenantId)
            ?: return Result.failure(BalanceAccountNotFound(query.accountId))

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
                val entry = line.monetaryEntry
                if (entry !is FiatEntry) continue
                if (entry.currency != account.currency) continue
                when (line.entryType) {
                    EntryType.DEBIT  -> debits  = debits  + entry.amount
                    EntryType.CREDIT -> credits = credits + entry.amount
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
                computedAt = Instant.now(clock),
            )
        )
    }
}
