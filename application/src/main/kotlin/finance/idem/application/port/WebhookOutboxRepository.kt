package finance.idem.application.port

import finance.idem.application.outbox.WebhookOutboxEntry

interface WebhookOutboxRepository {
    fun save(entry: WebhookOutboxEntry)
}
