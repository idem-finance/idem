package finance.idem.infrastructure

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.monetary.FiatEntry
import finance.idem.infrastructure.persistence.AccountRepositoryAdapter
import finance.idem.infrastructure.persistence.TransactionRepositoryAdapter
import java.time.Instant
import java.util.UUID

/**
 * Seeds a balanced 2-line BRL/PIX transaction (debit + credit account, both new) under
 * [tenantId] via the real adapters -- shared across integration tests that need "some
 * transaction belonging to a tenant" as a fixture rather than exercising the seeding path
 * itself.
 */
fun seedTransaction(
    accountAdapter: AccountRepositoryAdapter,
    transactionAdapter: TransactionRepositoryAdapter,
    tenantId: TenantId,
    amount: String = "50.00",
): Transaction {
    val debit = AccountId.generate()
    val credit = AccountId.generate()
    val now = Instant.now()
    accountAdapter.save(Account.create(debit, tenantId, "Debit", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
    accountAdapter.save(Account.create(credit, tenantId, "Credit", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"))

    val txId = TransactionId.generate()
    val monetaryAmount = MonetaryAmount.of(amount)
    val lines =
        listOf(
            JournalLine(
                id = UUID.randomUUID(),
                transactionId = txId,
                accountId = debit,
                tenantId = tenantId,
                entryType = EntryType.DEBIT,
                monetaryEntry = FiatEntry(monetaryAmount, FiatCurrency.BRL, PaymentRail.PIX),
                createdAt = now,
                createdBy = "test",
            ),
            JournalLine(
                id = UUID.randomUUID(),
                transactionId = txId,
                accountId = credit,
                tenantId = tenantId,
                entryType = EntryType.CREDIT,
                monetaryEntry = FiatEntry(monetaryAmount, FiatCurrency.BRL, PaymentRail.PIX),
                createdAt = now,
                createdBy = "test",
            ),
        )
    val transaction =
        Transaction.create(
            id = txId,
            tenantId = tenantId,
            idempotencyKey = "seed-${txId.value}",
            lines = lines,
            occurredAt = now,
            createdAt = now,
            createdBy = "test",
        )
    return transactionAdapter.save(transaction)
}
