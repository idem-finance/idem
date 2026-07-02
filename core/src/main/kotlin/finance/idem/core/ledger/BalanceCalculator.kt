package finance.idem.core.ledger

import finance.idem.core.EntryType
import finance.idem.core.MonetaryAmount
import finance.idem.core.monetary.FiatEntry

object BalanceCalculator {
    fun compute(
        account: Account,
        transactions: List<Transaction>,
    ): MonetaryAmount {
        var debits = MonetaryAmount.ZERO
        var credits = MonetaryAmount.ZERO

        for (tx in transactions) {
            for (line in tx.lines) {
                if (line.accountId != account.id) continue
                val entry = line.monetaryEntry
                if (entry !is FiatEntry || entry.currency != account.currency) continue
                when (line.entryType) {
                    EntryType.DEBIT -> debits += entry.amount
                    EntryType.CREDIT -> credits += entry.amount
                }
            }
        }

        return when (account.normalBalance) {
            EntryType.DEBIT -> debits - credits
            EntryType.CREDIT -> credits - debits
        }
    }
}
