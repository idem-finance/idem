package finance.idem.infrastructure.chain

import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.TenantId
import finance.idem.core.chain.FailedChainTransfer
import finance.idem.core.monetary.OnChainEntry
import java.time.Instant
import java.util.UUID

data class DetectedTransfer(
    val idempotencyKey: String,
    val entry: OnChainEntry,
    val watchedAddress: WatchedAddress,
    val chainKey: String,
    // Null for readers with no per-log granularity (e.g. Tron). EVM/Solana always set this —
    // it disambiguates multiple independent transfers within the same txHash/signature.
    val logIndex: Int? = null,
)

fun DetectedTransfer.toCommand(createdBy: String): PostTransactionCommand =
    PostTransactionCommand(
        tenantId = TenantId.of(watchedAddress.tenantId),
        idempotencyKey = idempotencyKey,
        lines =
            listOf(
                JournalLineRequest(AccountId.of(watchedAddress.debitAccountId), EntryType.DEBIT, entry),
                JournalLineRequest(AccountId.of(watchedAddress.creditAccountId), EntryType.CREDIT, entry),
            ),
        createdBy = createdBy,
        metadata =
            buildMap {
                put("chain_key", chainKey)
                logIndex?.let { put("log_index", it.toString()) }
            },
    )

fun DetectedTransfer.toFailedChainTransfer(
    chainKey: String,
    source: String,
    error: Throwable,
): FailedChainTransfer =
    FailedChainTransfer(
        id = UUID.randomUUID(),
        chainKey = chainKey,
        source = source,
        idempotencyKey = idempotencyKey,
        txHash = entry.txHash,
        blockNumber = entry.blockNumber,
        tenantId = TenantId.of(watchedAddress.tenantId),
        walletAddress = entry.walletAddress,
        tokenContract = entry.tokenContract,
        debitAccountId = UUID.fromString(watchedAddress.debitAccountId),
        creditAccountId = UUID.fromString(watchedAddress.creditAccountId),
        token = entry.token,
        amount = entry.amount,
        errorMessage = error.message ?: error.toString(),
        createdAt = Instant.now(),
    )
