package finance.idem.application.settlement

import finance.idem.core.ledger.EntryStatus

class SettlementAlreadyTerminal(
    val status: EntryStatus,
) : Exception("Settlement is already in terminal status $status and cannot be cancelled")
