package finance.idem.infrastructure.service

import finance.idem.application.ledger.AccountStatement
import finance.idem.application.ledger.BalanceAccountNotFound
import finance.idem.application.ledger.GenerateStatementQuery
import finance.idem.application.ledger.GenerateStatementUseCase
import finance.idem.application.ledger.InvalidStatementRange
import finance.idem.application.ledger.QueryBalanceQuery
import finance.idem.application.ledger.QueryBalanceUseCase
import finance.idem.application.ledger.StatementAccountNotFound
import finance.idem.application.ledger.StatementMovement
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.monetary.FiatEntry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GenerateStatementService(
    private val queryBalanceUseCase: QueryBalanceUseCase,
    private val transactionRepository: TransactionRepository,
) : GenerateStatementUseCase {

    override fun execute(query: GenerateStatementQuery): Result<AccountStatement> {
        if (query.from.isAfter(query.to)) {
            return Result.failure(InvalidStatementRange(query.from, query.to))
        }

        val opening = queryBalanceUseCase
            .execute(QueryBalanceQuery(query.accountId, query.tenantId, asOf = query.from))
            .getOrElse { error -> return Result.failure(error.toStatementError(query)) }

        val closing = queryBalanceUseCase
            .execute(QueryBalanceQuery(query.accountId, query.tenantId, asOf = query.to))
            .getOrElse { error -> return Result.failure(error.toStatementError(query)) }

        val movements = transactionRepository
            .findByAccountId(query.accountId, query.tenantId)
            .filter { it.occurredAt > query.from && it.occurredAt <= query.to }
            .sortedBy { it.occurredAt }
            .flatMap { tx ->
                tx.lines
                    .filter { it.accountId == query.accountId }
                    .mapNotNull { line ->
                        val entry = line.monetaryEntry
                        if (entry !is FiatEntry || entry.currency != opening.currency) return@mapNotNull null
                        StatementMovement(
                            transactionId = tx.id,
                            type = line.entryType,
                            amount = entry.amount,
                            description = line.description,
                            occurredAt = tx.occurredAt,
                        )
                    }
            }

        return Result.success(
            AccountStatement(
                accountId = query.accountId,
                currency = opening.currency,
                from = query.from,
                to = query.to,
                openingBalance = opening.amount,
                closingBalance = closing.amount,
                movements = movements,
            )
        )
    }

    private fun Throwable.toStatementError(query: GenerateStatementQuery): Throwable = when (this) {
        is BalanceAccountNotFound -> StatementAccountNotFound(query.accountId)
        else -> this
    }
}
