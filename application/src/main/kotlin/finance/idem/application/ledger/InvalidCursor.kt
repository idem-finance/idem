package finance.idem.application.ledger

class InvalidCursor(val cursor: String) :
    ListEntriesError("Invalid cursor: $cursor")
