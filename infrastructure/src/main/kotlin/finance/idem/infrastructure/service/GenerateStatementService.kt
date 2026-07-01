package finance.idem.infrastructure.service

import finance.idem.application.ledger.AccountStatement
import finance.idem.application.ledger.GenerateStatementQuery
import finance.idem.application.ledger.GenerateStatementUseCase
import finance.idem.application.ledger.InvalidStatementRange
import finance.idem.application.ledger.StatementAccountNotFound
import finance.idem.application.ledger.StatementMovement
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.BalanceCalculator
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.monetary.FiatEntry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GenerateStatementService(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
) : GenerateStatementUseCase {
    override fun execute(query: GenerateStatementQuery): Result<AccountStatement> {
        if (query.from.isAfter(query.to)) {
            return Result.failure(InvalidStatementRange(query.from, query.to))
        }

        val account =
            accountRepository.findById(query.accountId, query.tenantId)
                ?: return Result.failure(StatementAccountNotFound(query.accountId))

        val transactions = transactionRepository.findByAccountId(query.accountId, query.tenantId)

        val openingTransactions = transactions.filter { it.occurredAt <= query.from }
        val openingBalance = BalanceCalculator.compute(account, openingTransactions)

        val movementTransactions =
            transactions
                .filter { it.occurredAt > query.from && it.occurredAt <= query.to }
                .sortedBy { it.occurredAt }

        val movements =
            movementTransactions.flatMap { tx ->
                tx.lines
                    .filter { it.accountId == query.accountId }
                    .mapNotNull { line ->
                        val entry = line.monetaryEntry
                        if (entry !is FiatEntry || entry.currency != account.currency) return@mapNotNull null
                        StatementMovement(
                            transactionId = tx.id,
                            type = line.entryType,
                            amount = entry.amount,
                            description = line.description,
                            occurredAt = tx.occurredAt,
                        )
                    }
            }

        val netMovements = BalanceCalculator.compute(account, movementTransactions)
        val closingBalance = openingBalance + netMovements

        return Result.success(
            AccountStatement(
                accountId = query.accountId,
                currency = account.currency,
                from = query.from,
                to = query.to,
                openingBalance = openingBalance,
                closingBalance = closingBalance,
                movements = movements,
            ),
        )
    }
}
