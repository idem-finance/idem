package finance.idem.application.ledger

import finance.idem.core.EntryType
import finance.idem.core.MonetaryAmount
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.monetary.MonetaryEntry
import java.time.Clock
import java.time.Instant

class QueryBalanceUseCase(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val clock: Clock = Clock.systemUTC(),
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

        // TODO(perf): replace with BalanceRepository port backed by SQL aggregation (issue #12).
        //             Loading all transactions is O(N) in account history — not viable for
        //             high-volume nostro/settlement accounts.
        for (tx in transactions) {
            for (line in tx.lines) {
                if (line.accountId != query.accountId) continue
                // Only count fiat entries in the account's denomination.
                // OnChainEntry lines and mismatched currencies are skipped —
                // on-chain balances require a separate query.
                val entry = line.monetaryEntry
                if (entry !is MonetaryEntry.FiatEntry) continue
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
