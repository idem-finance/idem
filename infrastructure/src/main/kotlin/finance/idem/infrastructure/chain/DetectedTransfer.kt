package finance.idem.infrastructure.chain

import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.TenantId
import finance.idem.core.monetary.OnChainEntry

data class DetectedTransfer(
    val idempotencyKey: String,
    val entry: OnChainEntry,
    val watchedAddress: WatchedAddress,
)

fun DetectedTransfer.toCommand(createdBy: String): PostTransactionCommand =
    PostTransactionCommand(
        tenantId = TenantId.of(watchedAddress.tenantId),
        idempotencyKey = idempotencyKey,
        lines = listOf(
            JournalLineRequest(AccountId.of(watchedAddress.debitAccountId), EntryType.DEBIT, entry),
            JournalLineRequest(AccountId.of(watchedAddress.creditAccountId), EntryType.CREDIT, entry),
        ),
        createdBy = createdBy,
    )
