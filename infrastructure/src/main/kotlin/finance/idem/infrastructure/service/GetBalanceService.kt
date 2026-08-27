package finance.idem.infrastructure.service

import finance.idem.application.ledger.Balance
import finance.idem.application.ledger.BalanceAccountNotFound
import finance.idem.application.ledger.GetBalanceQuery
import finance.idem.application.ledger.GetBalanceUseCase
import finance.idem.core.MonetaryAmount
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.BalanceCalculator
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.SettlementRepository
import finance.idem.core.ledger.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
@Transactional(readOnly = true)
class GetBalanceService(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val settlementRepository: SettlementRepository,
    private val clock: Clock = Clock.systemUTC(),
) : GetBalanceUseCase {
    override fun execute(query: GetBalanceQuery): Result<Balance> {
        val account =
            accountRepository.findById(query.accountId, query.tenantId)
                ?: return Result.failure(BalanceAccountNotFound(query.accountId))

        val transactions =
            transactionRepository
                .findByAccountId(query.accountId, query.tenantId)
                .let { txs ->
                    val cutoff = query.asOf
                    if (cutoff != null) txs.filter { it.occurredAt <= cutoff } else txs
                }

        val net = BalanceCalculator.compute(account, transactions)
        val onChain = BalanceCalculator.computeOnChain(account, transactions)

        // Pending-finality breakdown: not yet reorg-safe, so reported separately rather than
        // silently folded into the confirmed figure — a caller (e.g. an agent) deciding whether
        // to act on funds needs to know part of the balance could still be reversed.
        // Transaction/JournalLine carry no EntryStatus of their own (Settlement is a fully
        // separate aggregate), so this is a second query rather than something BalanceCalculator
        // can compute — BalanceCalculator stays a pure Transaction/JournalLine calculator with
        // no Settlement dependency.
        val pendingByToken =
            settlementRepository
                .findByAccountIdAndStatus(query.tenantId, query.accountId, EntryStatus.WATCHING)
                .groupBy { it.token }
                .mapValues { (_, settlements) -> settlements.fold(MonetaryAmount.ZERO) { acc, s -> acc + s.amount } }
        val onChainWithPending =
            onChain.map { balance -> balance.copy(pendingFinalityAmount = pendingByToken[balance.token] ?: MonetaryAmount.ZERO) }

        return Result.success(
            Balance(
                accountId = account.id,
                currency = account.currency,
                amount = net,
                normalBalance = account.normalBalance,
                computedAt = Instant.now(clock),
                onChainBalances = onChainWithPending,
            ),
        )
    }
}
