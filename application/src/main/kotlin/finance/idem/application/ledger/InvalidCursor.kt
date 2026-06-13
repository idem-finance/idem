package finance.idem.application.ledger

class InvalidCursor(val cursor: String) :
    GetEntriesError("Invalid cursor: $cursor")
