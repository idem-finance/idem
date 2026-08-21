package finance.idem.infrastructure.service

import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.reconciliation.ReconciliationResult
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import finance.idem.core.ledger.Transaction
import finance.idem.core.ledger.TransactionStatus
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.OnChainEntry
import finance.idem.infrastructure.chain.FinalityPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class BasicReconciliationServiceTest {
    @Mock lateinit var settlementRepository: SettlementRepository

    @Mock lateinit var webhookOutboxRepository: WebhookOutboxRepository

    private val tenantId = TenantId.generate()
    private val debitAccountId = AccountId.generate() // Nostro / on-chain custody account
    private val creditAccountId = AccountId.generate() // customer-facing account

    private val usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val watchedWallet = "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS"
    private val txHash = "5j7s6XxnkqxAbcDE1234567890abcdefghijklmnopqrstuvwxyz1234567"

    private fun service(
        enabled: Boolean = true,
        matchingWindowHours: Long = 24,
    ): BasicReconciliationService = BasicReconciliationService(settlementRepository, webhookOutboxRepository, enabled, matchingWindowHours)

    private fun onChainEntry(
        amount: MonetaryAmount = MonetaryAmount.of("100.000000"),
        fromAddress: String? = null,
    ) = OnChainEntry(
        amount = amount,
        token = StablecoinToken.USDC,
        chainId = ChainId.SOLANA,
        txHash = txHash,
        blockNumber = 250_000_000L,
        walletAddress = watchedWallet,
        tokenContract = usdcMint,
        fromAddress = fromAddress,
    )

    private fun onChainTransaction(
        entry: OnChainEntry = onChainEntry(),
        createdBy: String = "system",
        metadata: Map<String, String> = emptyMap(),
    ): Transaction {
        val txId = TransactionId.generate()
        val now = Instant.now()
        val lines =
            listOf(
                JournalLine(UUID.randomUUID(), txId, debitAccountId, tenantId, EntryType.DEBIT, entry, null, now, createdBy),
                JournalLine(UUID.randomUUID(), txId, creditAccountId, tenantId, EntryType.CREDIT, entry, null, now, createdBy),
            )
        return Transaction.create(
            id = txId,
            tenantId = tenantId,
            idempotencyKey = "SOLANA:$txHash:2",
            lines = lines,
            occurredAt = now,
            createdAt = now,
            createdBy = createdBy,
            metadata = metadata,
        )
    }

    private fun fiatTransaction(): Transaction {
        val txId = TransactionId.generate()
        val now = Instant.now()
        val entry = FiatEntry(MonetaryAmount.of("1000.00"), FiatCurrency.BRL, PaymentRail.PIX)
        val lines =
            listOf(
                JournalLine(UUID.randomUUID(), txId, debitAccountId, tenantId, EntryType.DEBIT, entry, null, now, "system"),
                JournalLine(UUID.randomUUID(), txId, creditAccountId, tenantId, EntryType.CREDIT, entry, null, now, "system"),
            )
        return Transaction.create(
            id = txId,
            tenantId = tenantId,
            idempotencyKey = "fiat-001",
            lines = lines,
            occurredAt = now,
            createdAt = now,
            createdBy = "system",
        )
    }

    private fun pendingSettlement(
        amount: MonetaryAmount,
        createdAt: Instant,
        expectedFromAddress: String? = null,
    ) = Settlement(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        accountId = creditAccountId,
        amount = amount,
        token = StablecoinToken.USDC,
        chainId = ChainId.SOLANA,
        walletAddress = watchedWallet,
        status = EntryStatus.PENDING,
        expectedFromAddress = expectedFromAddress,
        createdAt = createdAt,
        createdBy = "api-user",
    )

    @BeforeEach
    fun setUp() {
        Mockito.lenient().`when`(settlementRepository.save(any())).thenAnswer { it.getArgument(0) }
    }

    @Test
    fun `returns NotApplicable when transaction has only FiatEntry lines`() {
        val result = service().reconcile(fiatTransaction())

        assertEquals(ReconciliationResult.NotApplicable, result)
        verify(settlementRepository, never()).findPendingCandidates(any(), any(), any(), any(), any(), any())
        verify(settlementRepository, never()).save(any())
    }

    @Test
    fun `returns NotApplicable when reconciliation is disabled`() {
        val result = service(enabled = false).reconcile(onChainTransaction())

        assertEquals(ReconciliationResult.NotApplicable, result)
        verify(settlementRepository, never()).findPendingCandidates(any(), any(), any(), any(), any(), any())
        verify(settlementRepository, never()).save(any())
    }

    @Test
    fun `match found settles the candidate and writes transaction-settled webhook`() {
        val tx = onChainTransaction()
        val candidate = pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(60))
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(candidate))

        val result = service().reconcile(tx)

        assertTrue(result is ReconciliationResult.Settled)
        val settled = (result as ReconciliationResult.Settled).settlement
        assertEquals(EntryStatus.SETTLED, settled.status)
        assertEquals(candidate.id, settled.id)
        assertEquals(tx.id, settled.matchedTransactionId)
        assertEquals(txHash, settled.txHash)
        assertEquals(250_000_000L, settled.blockNumber)
        assertNotNull(settled.confirmedAt)
        assertTrue(Duration.between(settled.confirmedAt, Instant.now()).abs().seconds < 5)

        val webhookCaptor = argumentCaptor<WebhookOutboxEntry>()
        verify(webhookOutboxRepository).save(webhookCaptor.capture())
        assertEquals("transaction.settled", webhookCaptor.firstValue.eventType)
    }

    @Test
    fun `webhook-sourced match goes to WATCHING, not SETTLED, and defers the outbox write`() {
        val tx =
            onChainTransaction(
                createdBy = FinalityPolicy.WEBHOOK_SOURCE,
                metadata = mapOf("chain_key" to "EVM_1", "log_index" to "2"),
            )
        val candidate = pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(60))
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(candidate))

        val result = service().reconcile(tx)

        assertTrue(result is ReconciliationResult.Settled)
        val settled = (result as ReconciliationResult.Settled).settlement
        assertEquals(EntryStatus.WATCHING, settled.status)
        assertEquals(tx.id, settled.matchedTransactionId)
        assertEquals(txHash, settled.txHash)
        assertEquals(250_000_000L, settled.blockNumber)
        assertEquals("EVM_1", settled.chainKey)
        assertEquals(2, settled.logIndex)
        assertEquals(null, settled.confirmedAt)

        verify(webhookOutboxRepository, never()).save(any())
    }

    @Test
    fun `webhook-sourced match with no metadata leaves chainKey and logIndex null`() {
        val tx = onChainTransaction(createdBy = FinalityPolicy.WEBHOOK_SOURCE)
        val candidate = pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(60))
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(candidate))

        val result = service().reconcile(tx)

        val settled = (result as ReconciliationResult.Settled).settlement
        assertEquals(null, settled.chainKey)
        assertEquals(null, settled.logIndex)
    }

    @Test
    fun `recovery-sourced match (chain-recovery) settles immediately, unaffected by the webhook gate`() {
        val tx = onChainTransaction(createdBy = "chain-recovery")
        val candidate = pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(60))
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(candidate))

        val result = service().reconcile(tx)

        val settled = (result as ReconciliationResult.Settled).settlement
        assertEquals(EntryStatus.SETTLED, settled.status)
        assertNotNull(settled.confirmedAt)
        verify(webhookOutboxRepository).save(any())
    }

    @Test
    fun `UNMATCHED settlement carries chainKey and logIndex from transaction metadata`() {
        val tx =
            onChainTransaction(
                createdBy = FinalityPolicy.WEBHOOK_SOURCE,
                metadata = mapOf("chain_key" to "EVM_8453", "log_index" to "1"),
            )
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(emptyList())

        val result = service().reconcile(tx)

        val unmatched = (result as ReconciliationResult.Unmatched).settlement
        assertEquals("EVM_8453", unmatched.chainKey)
        assertEquals(1, unmatched.logIndex)
    }

    @Test
    fun `no match flags an UNMATCHED settlement on the credit account and warns`() {
        val tx = onChainTransaction()
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(emptyList())

        val result = service().reconcile(tx)

        assertTrue(result is ReconciliationResult.Unmatched)
        val unmatched = (result as ReconciliationResult.Unmatched).settlement
        assertEquals(EntryStatus.UNMATCHED, unmatched.status)
        assertEquals(creditAccountId, unmatched.accountId)
        assertEquals(tx.id, unmatched.matchedTransactionId)
        assertEquals(txHash, unmatched.txHash)
        assertEquals(250_000_000L, unmatched.blockNumber)
        assertNotNull(unmatched.confirmedAt)
        assertEquals(MonetaryAmount.of("100.000000"), unmatched.amount)

        val webhookCaptor = argumentCaptor<WebhookOutboxEntry>()
        verify(webhookOutboxRepository).save(webhookCaptor.capture())
        assertEquals("reconciliation.unmatched", webhookCaptor.firstValue.eventType)
    }

    @Test
    fun `webhook-sourced UNMATCHED defers confirmedAt and the reconciliation-unmatched webhook`() {
        val tx =
            onChainTransaction(
                createdBy = FinalityPolicy.WEBHOOK_SOURCE,
                metadata = mapOf("chain_key" to "EVM_1", "log_index" to "2"),
            )
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(emptyList())

        val result = service().reconcile(tx)

        val unmatched = (result as ReconciliationResult.Unmatched).settlement
        assertEquals(EntryStatus.UNMATCHED, unmatched.status)
        assertEquals(null, unmatched.confirmedAt)
        verify(webhookOutboxRepository, never()).save(any())
    }

    @Test
    fun `recovery-sourced UNMATCHED settles confirmedAt immediately, unaffected by the webhook gate`() {
        val tx = onChainTransaction(createdBy = "chain-recovery")
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(emptyList())

        val result = service().reconcile(tx)

        val unmatched = (result as ReconciliationResult.Unmatched).settlement
        assertNotNull(unmatched.confirmedAt)
        verify(webhookOutboxRepository).save(any())
    }

    @Test
    fun `multiple equal-amount candidates settle the oldest one`() {
        val tx = onChainTransaction()
        val older = pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(7200))
        val newer = pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(60))
        // findPendingCandidates is documented to return rows ordered by createdAt ASC
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(older, newer))

        val result = service().reconcile(tx)

        assertTrue(result is ReconciliationResult.Settled)
        assertEquals(older.id, (result as ReconciliationResult.Settled).settlement.id)
    }

    @Test
    fun `candidate with different amount falls through to UNMATCHED`() {
        val tx = onChainTransaction(onChainEntry(MonetaryAmount.of("100.000000")))
        val candidate = pendingSettlement(MonetaryAmount.of("99.000000"), Instant.now().minusSeconds(60))
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(candidate))

        val result = service().reconcile(tx)

        assertTrue(result is ReconciliationResult.Unmatched)
    }

    @Test
    fun `findPendingCandidates is called with both debit and credit account ids`() {
        val tx = onChainTransaction()
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(emptyList())

        service().reconcile(tx)

        val accountIdsCaptor = argumentCaptor<Set<AccountId>>()
        // tenantId is a @JvmInline value class — at this non-generic parameter
        // position it is unboxed to a raw UUID at the JVM level, so eq(tenantId)
        // (which wraps a boxed TenantId) never matches. Use any() here and rely
        // on the other tests to cover the tenantId argument indirectly.
        verify(settlementRepository).findPendingCandidates(
            any(),
            accountIdsCaptor.capture(),
            eq(StablecoinToken.USDC),
            eq(ChainId.SOLANA),
            eq(watchedWallet),
            any(),
        )
        assertEquals(setOf(debitAccountId, creditAccountId), accountIdsCaptor.firstValue)
    }

    @Test
    fun `since passed to findPendingCandidates reflects the configured matching window`() {
        val tx = onChainTransaction()
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(emptyList())

        service(matchingWindowHours = 1).reconcile(tx)

        val sinceCaptor = argumentCaptor<Instant>()
        verify(settlementRepository).findPendingCandidates(any(), any(), any(), any(), any(), sinceCaptor.capture())
        val expected = Instant.now().minusSeconds(3600)
        assertTrue(Duration.between(sinceCaptor.firstValue, expected).abs().seconds < 5)
    }

    @Test
    fun `transaction with on-chain lines but no CREDIT line returns NotApplicable without throwing`() {
        val txId = TransactionId.generate()
        val now = Instant.now()
        val entry = onChainEntry()
        // Both on-chain lines are DEBIT — violates the implicit "one DEBIT/CREDIT
        // pair per transaction" convention. Transaction.validate() would reject this
        // (unbalanced currencyKey), so build via reconstitute() to exercise
        // BasicReconciliationService's own defensive guard in isolation.
        val lines =
            listOf(
                JournalLine(UUID.randomUUID(), txId, debitAccountId, tenantId, EntryType.DEBIT, entry, null, now, "system"),
                JournalLine(UUID.randomUUID(), txId, creditAccountId, tenantId, EntryType.DEBIT, entry, null, now, "system"),
            )
        val tx =
            Transaction.reconstitute(
                id = txId,
                tenantId = tenantId,
                idempotencyKey = "SOLANA:$txHash:2",
                lines = lines,
                status = TransactionStatus.PENDING,
                occurredAt = now,
                createdAt = now,
                createdBy = "system",
            )
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(emptyList())

        val result = service().reconcile(tx)

        assertEquals(ReconciliationResult.NotApplicable, result)
        verify(settlementRepository, never()).save(any())
        verify(webhookOutboxRepository, never()).save(any())
    }

    @Test
    fun `amount match is scale-insensitive`() {
        val tx = onChainTransaction(onChainEntry(MonetaryAmount.of("100.00")))
        val candidate = pendingSettlement(MonetaryAmount.of("100"), Instant.now().minusSeconds(60))
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(candidate))

        val result = service().reconcile(tx)

        assertTrue(result is ReconciliationResult.Settled)
    }

    @Test
    fun `sender-confirmed candidate wins over an older amount-only candidate`() {
        val tx = onChainTransaction(onChainEntry(fromAddress = "0xsender"))
        val older = pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(7200))
        val newer = pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(3600), expectedFromAddress = "0xsender")
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(older, newer))

        val result = service().reconcile(tx)

        assertTrue(result is ReconciliationResult.Settled)
        assertEquals(newer.id, (result as ReconciliationResult.Settled).settlement.id)
    }

    @Test
    fun `mismatched expectedFromAddress excludes a candidate even on amount match`() {
        val tx = onChainTransaction(onChainEntry(fromAddress = "0xbob"))
        val candidate = pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(60), expectedFromAddress = "0xalice")
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(candidate))

        val result = service().reconcile(tx)

        assertTrue(result is ReconciliationResult.Unmatched)
    }

    @Test
    fun `sender-confirmed match takes precedence over an older FIFO-eligible candidate`() {
        val tx = onChainTransaction(onChainEntry(fromAddress = "0xsender"))
        val fifoEligible = pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(7200))
        val senderConfirmed =
            pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(60), expectedFromAddress = "0xsender")
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(fifoEligible, senderConfirmed))

        val result = service().reconcile(tx)

        assertTrue(result is ReconciliationResult.Settled)
        assertEquals(senderConfirmed.id, (result as ReconciliationResult.Settled).settlement.id)
    }

    @Test
    fun `sender-confirmed match is case-insensitive for Tron-style lowercase fromAddress`() {
        // TronChainReader stores fromAddress lowercased (e.g. "TJCnKsPa..." ->
        // "tjcnkspa..."), but an operator registers expectedFromAddress in the
        // standard mixed-case Base58 form they were given. Tier 1 must still match.
        val tx = onChainTransaction(onChainEntry(fromAddress = "tjcnkspa7y5okkxvqaidzbzqx3qyq6sxmw"))
        val candidate =
            pendingSettlement(
                MonetaryAmount.of("100.000000"),
                Instant.now().minusSeconds(60),
                expectedFromAddress = "TJCnKsPa7y5okkXvQAidZBzqx3QyQ6sxMW",
            )
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(candidate))

        val result = service().reconcile(tx)

        assertTrue(result is ReconciliationResult.Settled)
        assertEquals(candidate.id, (result as ReconciliationResult.Settled).settlement.id)
    }

    @Test
    fun `null onChainEntry fromAddress excludes candidates with a registered expectedFromAddress`() {
        val tx = onChainTransaction(onChainEntry(fromAddress = null))
        val candidate = pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(60), expectedFromAddress = "0xsender")
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(candidate))

        val result = service().reconcile(tx)

        assertTrue(result is ReconciliationResult.Unmatched)
    }

    @Test
    fun `oldest tier-2-eligible candidate wins over a newer sender-mismatched candidate`() {
        val tx = onChainTransaction(onChainEntry(fromAddress = "0xbob"))
        val fifoEligible = pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(7200))
        val senderMismatched =
            pendingSettlement(MonetaryAmount.of("100.000000"), Instant.now().minusSeconds(60), expectedFromAddress = "0xalice")
        whenever(settlementRepository.findPendingCandidates(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(fifoEligible, senderMismatched))

        val result = service().reconcile(tx)

        assertTrue(result is ReconciliationResult.Settled)
        assertEquals(fifoEligible.id, (result as ReconciliationResult.Settled).settlement.id)
    }
}
