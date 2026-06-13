package finance.idem.application.ledger

import java.time.Instant

class InvalidStatementRange(val from: Instant, val to: Instant) :
    GenerateStatementError("from must not be after to: from=$from, to=$to")
