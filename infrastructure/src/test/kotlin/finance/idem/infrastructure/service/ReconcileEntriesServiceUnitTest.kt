package finance.idem.infrastructure.service

import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.reconciliation.ReconcileEntriesCommand
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReconcileEntriesServiceUnitTest {
    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()
    private val now = Instant.now()

    // Pass-through transaction manager — no real DB needed for unit tests.
    private val txManager =
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

            override fun commit(status: TransactionStatus) = Unit

            override fun rollback(status: TransactionStatus) = Unit
        }

    private val savedOutbox = mutableListOf<WebhookOutboxEntry>()
    private val outboxRepo =
        object : WebhookOutboxRepository {
            override fun save(entry: WebhookOutboxEntry) {
                savedOutbox.add(entry)
            }

            override fun findDispatchable(limit: Int) = emptyList<finance.idem.application.outbox.WebhookOutboxDispatch>()

            override fun markDelivered(
                id: UUID,
                tenantId: TenantId,
            ) = Unit

            override fun markFailedForRetry(
                id: UUID,
                tenantId: TenantId,
                attempts: Int,
                nextRetryAt: Instant,
                lastError: String?,
            ) = Unit

            override fun markDead(
                id: UUID,
                tenantId: TenantId,
                lastError: String?,
            ) = Unit
        }

    private fun unmatchedSettlement(
        walletAddress: String = "wallet-1",
        token: StablecoinToken = StablecoinToken.USDC,
        confirmedAt: Instant? = now.minusSeconds(30),
        chainKey: String? = "SOLANA_1",
        logIndex: Int? = 0,
    ) = Settlement(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        accountId = accountId,
        amount = MonetaryAmount.of("100.000000"),
        token = token,
        chainId = ChainId.SOLANA,
        walletAddress = walletAddress,
        status = EntryStatus.UNMATCHED,
        matchedTransactionId = TransactionId.generate(),
        txHash = "hash-${UUID.randomUUID()}",
        blockNumber = 1L,
        confirmedAt = confirmedAt,
        createdAt = now,
        createdBy = "system",
        chainKey = chainKey,
        logIndex = logIndex,
    )

    private fun pendingSettlement(
        walletAddress: String = "wallet-1",
        token: StablecoinToken = StablecoinToken.USDC,
    ) = Settlement(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        accountId = accountId,
        amount = MonetaryAmount.of("100.000000"),
        token = token,
        chainId = ChainId.SOLANA,
        walletAddress = walletAddress,
        status = EntryStatus.PENDING,
        createdAt = now,
        createdBy = "system",
    )

    private fun cmd() =
        ReconcileEntriesCommand(
            tenantId = tenantId,
            accountId = null,
            from = now.minusSeconds(3600),
            to = now.plusSeconds(3600),
        )

    private val emptySettlementRepo =
        object : SettlementRepository {
            override fun save(settlement: Settlement) = settlement

            override fun findById(
                id: UUID,
                tenantId: TenantId,
            ): Settlement? = null

            override fun findUnmatchedInWindow(
                tenantId: TenantId,
                accountId: AccountId?,
                from: Instant,
                to: Instant,
            ) = emptyList<Settlement>()

            override fun findPendingCandidates(
                tenantId: TenantId,
                accountIds: Set<AccountId>,
                token: StablecoinToken,
                chainId: ChainId,
                walletAddress: String,
                since: Instant,
            ) = emptyList<Settlement>()

            override fun findPage(
                tenantId: TenantId,
                status: finance.idem.core.ledger.EntryStatus?,
                from: Instant?,
                to: Instant?,
                afterCreatedAt: Instant?,
                afterId: UUID?,
                limit: Int,
            ) = emptyList<Settlement>()

            override fun findReversibleByTxHashAndLogIndex(
                tenantId: TenantId,
                txHash: String,
                logIndex: Int,
            ): Settlement? = null

            override fun findReorgedByTxHashAndLogIndex(
                tenantId: TenantId,
                txHash: String,
                logIndex: Int,
            ): Settlement? = null

            override fun findPendingFinalitySweep(
                tenantId: TenantId,
                chainKey: String,
                upToBlock: Long,
            ) = emptyList<Settlement>()

            override fun findByAccountIdAndStatus(
                tenantId: TenantId,
                accountId: AccountId,
                status: finance.idem.core.ledger.EntryStatus,
            ) = emptyList<Settlement>()

            override fun markReorged(
                id: UUID,
                tenantId: TenantId,
                reversalTransactionId: TransactionId,
                reorgedAt: Instant,
            ): Boolean = false
        }

    @Test
    fun `tolerancePercent above 100 is rejected`() {
        val service = ReconcileEntriesService(emptySettlementRepo, outboxRepo, txManager, BigDecimal.ZERO)
        assertFailsWith<IllegalArgumentException> {
            service.execute(cmd().copy(tolerancePercent = BigDecimal("100.01"))).getOrThrow()
        }
    }

    @Test
    fun `negative tolerancePercent is rejected`() {
        val service = ReconcileEntriesService(emptySettlementRepo, outboxRepo, txManager, BigDecimal.ZERO)
        assertFailsWith<IllegalArgumentException> {
            service.execute(cmd().copy(tolerancePercent = BigDecimal("-0.01"))).getOrThrow()
        }
    }

    @Test
    fun `tolerancePercent at boundary values 0 and 100 are accepted`() {
        val service = ReconcileEntriesService(emptySettlementRepo, outboxRepo, txManager, BigDecimal.ZERO)
        service.execute(cmd().copy(tolerancePercent = BigDecimal.ZERO)).getOrThrow()
        service.execute(cmd().copy(tolerancePercent = BigDecimal("100"))).getOrThrow()
    }

    @Test
    fun `DB exception in processGroup is caught, entry recorded as Failed, remaining groups continue`() {
        val failingEntry = unmatchedSettlement(walletAddress = "wallet-1", token = StablecoinToken.USDC)
        val survivingEntry = unmatchedSettlement(walletAddress = "wallet-2", token = StablecoinToken.USDT)

        val repo =
            object : SettlementRepository {
                override fun save(settlement: Settlement) = settlement

                override fun findById(
                    id: UUID,
                    tenantId: TenantId,
                ): Settlement? = null

                override fun findUnmatchedInWindow(
                    tenantId: TenantId,
                    accountId: AccountId?,
                    from: Instant,
                    to: Instant,
                ) = listOf(failingEntry, survivingEntry)

                override fun findPendingCandidates(
                    tenantId: TenantId,
                    accountIds: Set<AccountId>,
                    token: StablecoinToken,
                    chainId: ChainId,
                    walletAddress: String,
                    since: Instant,
                ): List<Settlement> {
                    if (token == StablecoinToken.USDC) throw RuntimeException("simulated DB failure")
                    return emptyList()
                }

                override fun findPage(
                    tenantId: TenantId,
                    status: finance.idem.core.ledger.EntryStatus?,
                    from: Instant?,
                    to: Instant?,
                    afterCreatedAt: Instant?,
                    afterId: UUID?,
                    limit: Int,
                ) = emptyList<Settlement>()

                override fun findReversibleByTxHashAndLogIndex(
                    tenantId: TenantId,
                    txHash: String,
                    logIndex: Int,
                ): Settlement? = null

                override fun findReorgedByTxHashAndLogIndex(
                    tenantId: TenantId,
                    txHash: String,
                    logIndex: Int,
                ): Settlement? = null

                override fun findPendingFinalitySweep(
                    tenantId: TenantId,
                    chainKey: String,
                    upToBlock: Long,
                ) = emptyList<Settlement>()

                override fun findByAccountIdAndStatus(
                    tenantId: TenantId,
                    accountId: AccountId,
                    status: finance.idem.core.ledger.EntryStatus,
                ) = emptyList<Settlement>()

                override fun markReorged(
                    id: UUID,
                    tenantId: TenantId,
                    reversalTransactionId: TransactionId,
                    reorgedAt: Instant,
                ): Boolean = false
            }

        val service = ReconcileEntriesService(repo, outboxRepo, txManager, BigDecimal.ZERO)
        val result = service.execute(cmd()).getOrThrow()

        assertEquals(0, result.matched)
        assertEquals(2, result.unmatched)
        assertEquals(2, result.exceptions.size)

        val failedEx = result.exceptions.first { it.settlementId == failingEntry.id }
        assertTrue(failedEx.reason.contains("simulated DB failure"))

        // survivingEntry has no PENDING candidates → reconciliation.exception outbox (not Failed).
        val survivingEx = result.exceptions.first { it.settlementId == survivingEntry.id }
        assertEquals("No matching pending settlement found", survivingEx.reason)

        // Outbox written only for the surviving (Unmatched) group, not for the Failed one.
        assertEquals(1, savedOutbox.size)
        assertEquals("reconciliation.exception", savedOutbox[0].eventType)
    }

    private fun matchingRepo(
        entry: Settlement,
        candidate: Settlement,
        saved: MutableList<Settlement>,
    ) = object : SettlementRepository {
        override fun save(settlement: Settlement): Settlement {
            saved.add(settlement)
            return settlement
        }

        override fun findById(
            id: UUID,
            tenantId: TenantId,
        ): Settlement? = null

        override fun findUnmatchedInWindow(
            tenantId: TenantId,
            accountId: AccountId?,
            from: Instant,
            to: Instant,
        ) = listOf(entry)

        override fun findPendingCandidates(
            tenantId: TenantId,
            accountIds: Set<AccountId>,
            token: StablecoinToken,
            chainId: ChainId,
            walletAddress: String,
            since: Instant,
        ) = listOf(candidate)

        override fun findPage(
            tenantId: TenantId,
            status: EntryStatus?,
            from: Instant?,
            to: Instant?,
            afterCreatedAt: Instant?,
            afterId: UUID?,
            limit: Int,
        ) = emptyList<Settlement>()

        override fun findReversibleByTxHashAndLogIndex(
            tenantId: TenantId,
            txHash: String,
            logIndex: Int,
        ): Settlement? = null

        override fun findReorgedByTxHashAndLogIndex(
            tenantId: TenantId,
            txHash: String,
            logIndex: Int,
        ): Settlement? = null

        override fun findPendingFinalitySweep(
            tenantId: TenantId,
            chainKey: String,
            upToBlock: Long,
        ) = emptyList<Settlement>()

        override fun findByAccountIdAndStatus(
            tenantId: TenantId,
            accountId: AccountId,
            status: EntryStatus,
        ) = emptyList<Settlement>()

        override fun markReorged(
            id: UUID,
            tenantId: TenantId,
            reversalTransactionId: TransactionId,
            reorgedAt: Instant,
        ): Boolean = false
    }

    @Test
    fun `webhook-sourced match still pending finality settles both rows to WATCHING, not SETTLED`() {
        val entry = unmatchedSettlement(confirmedAt = null, chainKey = "EVM_1", logIndex = 5)
        val candidate = pendingSettlement()
        val saved = mutableListOf<Settlement>()

        val service = ReconcileEntriesService(matchingRepo(entry, candidate, saved), outboxRepo, txManager, BigDecimal.ZERO)
        val result = service.execute(cmd()).getOrThrow()

        assertEquals(1, result.matched)
        assertTrue(saved.all { it.status == EntryStatus.WATCHING })
        assertTrue(saved.all { it.confirmedAt == null })
        // The PENDING-side row needs chainKey/logIndex for SettlementFinalityPoller.verifyLogStillPresent
        // to work once it sweeps this WATCHING row on a later tick.
        val matchRow = saved.first { it.id == candidate.id }
        assertEquals("EVM_1", matchRow.chainKey)
        assertEquals(5, matchRow.logIndex)
        // Deferred to SettlementFinalityPoller — must not fire early for an unconfirmed match.
        assertTrue(savedOutbox.none { it.eventType == "transaction.settled" })
    }

    @Test
    fun `already finality-confirmed match settles both rows to SETTLED immediately`() {
        val entry = unmatchedSettlement(confirmedAt = now.minusSeconds(30))
        val candidate = pendingSettlement()
        val saved = mutableListOf<Settlement>()

        val service = ReconcileEntriesService(matchingRepo(entry, candidate, saved), outboxRepo, txManager, BigDecimal.ZERO)
        val result = service.execute(cmd()).getOrThrow()

        assertEquals(1, result.matched)
        assertTrue(saved.all { it.status == EntryStatus.SETTLED })
        assertTrue(saved.all { it.confirmedAt != null })
        assertTrue(savedOutbox.any { it.eventType == "transaction.settled" })
    }
}
