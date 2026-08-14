package finance.idem.core.ledger

import finance.idem.core.EntryType
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.OnChainEntry

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

    // Net per StablecoinToken, across all chains — never combined with the fiat balance
    // above, since a token amount and a fiat amount are not fungible units.
    fun computeOnChain(
        account: Account,
        transactions: List<Transaction>,
    ): List<OnChainBalance> {
        val debits = mutableMapOf<StablecoinToken, MonetaryAmount>()
        val credits = mutableMapOf<StablecoinToken, MonetaryAmount>()

        for (tx in transactions) {
            for (line in tx.lines) {
                if (line.accountId != account.id) continue
                val entry = line.monetaryEntry
                if (entry !is OnChainEntry) continue
                val byToken = if (line.entryType == EntryType.DEBIT) debits else credits
                byToken[entry.token] = (byToken[entry.token] ?: MonetaryAmount.ZERO) + entry.amount
            }
        }

        return (debits.keys + credits.keys)
            .distinct()
            .sortedBy { it.name }
            .map { token ->
                val d = debits[token] ?: MonetaryAmount.ZERO
                val c = credits[token] ?: MonetaryAmount.ZERO
                val net =
                    when (account.normalBalance) {
                        EntryType.DEBIT -> d - c
                        EntryType.CREDIT -> c - d
                    }
                OnChainBalance(token, net)
            }
    }
}
