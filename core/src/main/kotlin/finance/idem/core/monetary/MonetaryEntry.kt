package finance.idem.core.monetary

import finance.idem.core.MonetaryAmount

sealed class MonetaryEntry {
    abstract val amount: MonetaryAmount
}
