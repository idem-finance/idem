package finance.idem.application.ledger

import finance.idem.application.audit.AuditEntry
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.AuditRepository
import finance.idem.application.port.IdempotencyStore
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.core.LedgerInvariantViolation
import finance.idem.core.TransactionId
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.ledger.TransactionStatus
import java.time.Instant
import java.util.UUID

class PostTransactionUseCase(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val auditRepository: AuditRepository,
    private val webhookOutboxRepository: WebhookOutboxRepository,
    private val idempotencyStore: IdempotencyStore,
) {
    fun execute(cmd: PostTransactionCommand): Result<TransactionId> {
        // 1. Idempotency check — short-circuit on duplicate key
        idempotencyStore.find(cmd.idempotencyKey, cmd.tenantId)?.let { existingId ->
            val existing = transactionRepository.findById(existingId, cmd.tenantId)
            return when (existing?.status) {
                TransactionStatus.COMMITTED -> Result.success(existingId)
                else -> Result.failure(PostTransactionError.IdempotencyConflict(cmd.idempotencyKey))
            }
        }

        // 2. Validate all account IDs exist for this tenant
        for (req in cmd.lines) {
            if (!accountRepository.existsById(req.accountId, cmd.tenantId)) {
                return Result.failure(PostTransactionError.AccountNotFound(req.accountId))
            }
        }

        // 3. Build the transaction — double-entry invariant enforced here
        val txId = TransactionId.generate()
        val now = Instant.now()
        val lines = cmd.lines.map { req ->
            JournalLine(
                id = UUID.randomUUID(),
                transactionId = txId,
                accountId = req.accountId,
                tenantId = cmd.tenantId,
                entryType = req.entryType,
                monetaryEntry = req.monetaryEntry,
                description = req.description,
                createdAt = now,
                createdBy = cmd.createdBy,
            )
        }

        val transaction = try {
            Transaction.create(
                id = txId,
                tenantId = cmd.tenantId,
                idempotencyKey = cmd.idempotencyKey,
                lines = lines,
                occurredAt = now,
                createdAt = now,
                createdBy = cmd.createdBy,
                agentContext = cmd.agentContext,
                metadata = cmd.metadata,
            )
        } catch (e: LedgerInvariantViolation) {
            return Result.failure(PostTransactionError.InvariantViolation(e.message ?: "Ledger invariant violated"))
        }

        // 4-6. Three writes — all within the same @Transactional at the calling layer
        transactionRepository.save(transaction)
        auditRepository.save(AuditEntry.from(transaction, cmd.agentContext, cmd.createdBy))
        webhookOutboxRepository.save(WebhookOutboxEntry.transactionCommitted(transaction))

        // 7. Record idempotency result — after successful writes
        idempotencyStore.record(cmd.idempotencyKey, cmd.tenantId, transaction.id)

        return Result.success(transaction.id)
    }
}
