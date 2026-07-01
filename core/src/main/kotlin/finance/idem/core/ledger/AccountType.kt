package finance.idem.core.ledger

import finance.idem.core.EntryType

enum class AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE,
    ;

    /** Normal balance is determined by account type — never set by the caller. */
    fun normalBalance(): EntryType =
        when (this) {
            ASSET, EXPENSE -> EntryType.DEBIT
            LIABILITY, EQUITY, REVENUE -> EntryType.CREDIT
        }
}
