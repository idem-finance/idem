package finance.idem.infrastructure.service

import finance.idem.application.audit.AuditEntry
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionError
import finance.idem.application.ledger.PostTransactionUseCase
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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@Transactional
class PostTransactionService(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val auditRepository: AuditRepository,
    private val webhookOutboxRepository: WebhookOutboxRepository,
    private val idempotencyStore: IdempotencyStore,
) : PostTransactionUseCase {

    override fun execute(cmd: PostTransactionCommand): Result<TransactionId> {
        val txId = TransactionId.generate()
        val now = Instant.now()

        if (!idempotencyStore.tryRecord(cmd.idempotencyKey, cmd.tenantId, txId)) {
            val existingId = idempotencyStore.find(cmd.idempotencyKey, cmd.tenantId)
                ?: return Result.failure(PostTransactionError.IdempotencyConflict(cmd.idempotencyKey))
            val existing = transactionRepository.findById(existingId, cmd.tenantId)
            when (existing?.status) {
                TransactionStatus.COMMITTED ->
                    return Result.success(existingId)
                TransactionStatus.PENDING ->
                    return Result.failure(PostTransactionError.IdempotencyConflict(cmd.idempotencyKey))
                TransactionStatus.ROLLED_BACK -> {
                    idempotencyStore.release(cmd.idempotencyKey, cmd.tenantId)
                    idempotencyStore.tryRecord(cmd.idempotencyKey, cmd.tenantId, txId)
                }
                null ->
                    return Result.failure(PostTransactionError.IdempotencyConflict(cmd.idempotencyKey))
            }
        }

        val requestedIds = cmd.lines.map { it.accountId }.toSet()
        val existingIds = accountRepository.findExistingIds(requestedIds, cmd.tenantId)
        val missingId = requestedIds.firstOrNull { it !in existingIds }
        if (missingId != null) {
            return Result.failure(PostTransactionError.AccountNotFound(missingId))
        }

        val lines = cmd.lines.map { req: JournalLineRequest ->
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

        val committed = transaction.commit()
        transactionRepository.save(committed)
        auditRepository.save(AuditEntry.from(committed, cmd.agentContext, cmd.createdBy))
        webhookOutboxRepository.save(WebhookOutboxEntry.transactionCommitted(committed))

        return Result.success(committed.id)
    }
}
