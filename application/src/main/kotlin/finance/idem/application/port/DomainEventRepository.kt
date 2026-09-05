package finance.idem.application.port

import finance.idem.application.events.DomainEvent

interface DomainEventRepository {
    fun save(event: DomainEvent)
}
