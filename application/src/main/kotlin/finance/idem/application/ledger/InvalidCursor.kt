package finance.idem.application.ledger

class InvalidCursor(val cursor: String) :
    QueryEntriesError("Invalid cursor: $cursor")
