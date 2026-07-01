package finance.idem.application.settlement

import java.util.UUID

class SettlementNotFound(val id: UUID) : Exception("Settlement $id not found")
