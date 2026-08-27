package finance.idem.application.reconciliation

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReorgReversalModelsTest {
    private val tenantId = TenantId.generate()
    private val now = Instant.now()

    @Test
    fun `ReorgReversalCommand holds fields`() {
        val cmd = ReorgReversalCommand(tenantId, "0xabc", 2, "EVM_1", "removed=true")

        assertEquals(tenantId, cmd.tenantId)
        assertEquals("0xabc", cmd.txHash)
        assertEquals(2, cmd.logIndex)
        assertEquals("EVM_1", cmd.chainKey)
        assertEquals("removed=true", cmd.reason)
        assertEquals(cmd, cmd.copy())
    }

    @Test
    fun `ReorgReversalResult Reversed holds settlement and reversalTransactionId`() {
        val reversalTxId = TransactionId.generate()
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("100.000000"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = "0xabc",
                status = EntryStatus.REORGED,
                reversalTransactionId = reversalTxId,
                reorgedAt = now,
                createdAt = now,
                createdBy = "system",
            )

        val result = ReorgReversalResult.Reversed(settlement, reversalTxId)

        assertIs<ReorgReversalResult>(result)
        assertEquals(settlement, result.settlement)
        assertEquals(reversalTxId, result.reversalTransactionId)
    }

    @Test
    fun `ReorgReversalResult NoMatchingSettlement and AlreadyReorged are singletons`() {
        assertEquals(ReorgReversalResult.NoMatchingSettlement, ReorgReversalResult.NoMatchingSettlement)
        assertEquals(ReorgReversalResult.AlreadyReorged, ReorgReversalResult.AlreadyReorged)
    }

    @Test
    fun `ReorgReversalResult AlreadyCompensatedByRollback holds settlement and rollbackTransactionId`() {
        val rollbackTxId = TransactionId.generate()
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("100.000000"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = "0xabc",
                status = EntryStatus.REORGED,
                reversalTransactionId = rollbackTxId,
                reorgedAt = now,
                createdAt = now,
                createdBy = "system",
            )

        val result = ReorgReversalResult.AlreadyCompensatedByRollback(settlement, rollbackTxId)

        assertIs<ReorgReversalResult>(result)
        assertEquals(settlement, result.settlement)
        assertEquals(rollbackTxId, result.rollbackTransactionId)
        assertEquals(result, result.copy())
        assertEquals(result.hashCode(), result.copy().hashCode())
        assertEquals(result.toString(), result.copy().toString())
    }
}
