package finance.idem.api.settlement

import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class SettlementResponse(
    val settlementId: UUID,
    val accountId: UUID,
    val status: String,
    val expectedToken: String,
    val expectedAmount: String,
    val expectedWalletAddress: String,
    val expectedChainId: String,
    val expectedFromAddress: String?,
    val matchedTransactionId: UUID?,
    val txHash: String?,
    val blockNumber: Long?,
    val confirmedAt: Instant?,
    val expiresAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        fun from(settlement: Settlement, matchWindowHours: Long): SettlementResponse =
            SettlementResponse(
                settlementId = settlement.id,
                accountId = settlement.accountId.value,
                status = settlement.status.name,
                expectedToken = settlement.token.name,
                expectedAmount = settlement.amount.value.toPlainString(),
                expectedWalletAddress = settlement.walletAddress,
                expectedChainId = settlement.chainId.name,
                expectedFromAddress = settlement.expectedFromAddress,
                matchedTransactionId = settlement.matchedTransactionId?.value,
                txHash = settlement.txHash,
                blockNumber = settlement.blockNumber,
                confirmedAt = settlement.confirmedAt,
                expiresAt = if (settlement.status == EntryStatus.PENDING)
                    settlement.createdAt.plus(matchWindowHours, ChronoUnit.HOURS)
                else null,
                createdAt = settlement.createdAt,
            )
    }
}
