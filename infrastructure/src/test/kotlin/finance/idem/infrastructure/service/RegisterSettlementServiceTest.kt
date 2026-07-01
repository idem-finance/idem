package finance.idem.infrastructure.service

import finance.idem.application.port.SettlementIdempotencyStore
import finance.idem.application.settlement.AccountNotFoundForSettlement
import finance.idem.application.settlement.RegisterSettlementCommand
import finance.idem.application.settlement.SettlementIdempotencyConflict
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegisterSettlementServiceTest {

    private val accountRepository: AccountRepository = mock()
    private val settlementRepository: SettlementRepository = mock()
    private val settlementIdempotencyStore: SettlementIdempotencyStore = mock()
    private val service = RegisterSettlementService(accountRepository, settlementRepository, settlementIdempotencyStore)

    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()

    private fun cmd(expectedFromAddress: String? = null, idempotencyKey: String = "idem-key-001") = RegisterSettlementCommand(
        tenantId = tenantId,
        accountId = accountId,
        amount = MonetaryAmount.of("250.00"),
        token = StablecoinToken.USDC,
        chainId = ChainId.SOLANA,
        walletAddress = "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS",
        expectedFromAddress = expectedFromAddress,
        createdBy = "api-user",
        idempotencyKey = idempotencyKey,
    )

    @Test
    fun `returns AccountNotFoundForSettlement when account does not exist`() {
        whenever(settlementIdempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.existsById(accountId, tenantId)).thenReturn(false)

        val result = service.execute(cmd())

        assertTrue(result.isFailure)
        assertIs<AccountNotFoundForSettlement>(result.exceptionOrNull())
        verify(settlementRepository, never()).save(any())
    }

    @Test
    fun `saves and returns a PENDING settlement when account exists`() {
        whenever(settlementIdempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.existsById(accountId, tenantId)).thenReturn(true)
        val captor = argumentCaptor<Settlement>()
        whenever(settlementRepository.save(captor.capture())).thenAnswer { captor.firstValue }

        val result = service.execute(cmd())

        assertTrue(result.isSuccess)
        val saved = captor.firstValue
        assertEquals(tenantId, saved.tenantId)
        assertEquals(accountId, saved.accountId)
        assertEquals(MonetaryAmount.of("250.00"), saved.amount)
        assertEquals(StablecoinToken.USDC, saved.token)
        assertEquals(ChainId.SOLANA, saved.chainId)
        assertEquals(EntryStatus.PENDING, saved.status)
        assertNull(saved.expectedFromAddress)
    }

    @Test
    fun `persists expectedFromAddress when provided`() {
        whenever(settlementIdempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.existsById(accountId, tenantId)).thenReturn(true)
        val captor = argumentCaptor<Settlement>()
        whenever(settlementRepository.save(captor.capture())).thenAnswer { captor.firstValue }

        service.execute(cmd(expectedFromAddress = "0xSender"))

        assertEquals("0xSender", captor.firstValue.expectedFromAddress)
    }

    @Test
    fun `returns cached settlement when idempotency key already claimed and settlement exists`() {
        val existingId = UUID.randomUUID()
        val existing = Settlement(
            id = existingId,
            tenantId = tenantId,
            accountId = accountId,
            amount = MonetaryAmount.of("250.00"),
            token = StablecoinToken.USDC,
            chainId = ChainId.SOLANA,
            walletAddress = "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS",
            status = EntryStatus.PENDING,
            createdAt = Instant.now(),
            createdBy = "api-user",
        )
        whenever(settlementIdempotencyStore.tryRecord(any(), any(), any())).thenReturn(false)
        whenever(settlementIdempotencyStore.find("idem-key-001", tenantId)).thenReturn(existingId)
        whenever(settlementRepository.findById(existingId, tenantId)).thenReturn(existing)

        val result = service.execute(cmd())

        assertTrue(result.isSuccess)
        assertEquals(existing, result.getOrNull())
        verify(accountRepository, never()).existsById(any(), any())
        verify(settlementRepository, never()).save(any())
    }

    @Test
    fun `returns SettlementIdempotencyConflict when key claimed but not found in store`() {
        whenever(settlementIdempotencyStore.tryRecord(any(), any(), any())).thenReturn(false)
        whenever(settlementIdempotencyStore.find("idem-key-001", tenantId)).thenReturn(null)

        val result = service.execute(cmd())

        assertTrue(result.isFailure)
        assertIs<SettlementIdempotencyConflict>(result.exceptionOrNull())
    }

    @Test
    fun `returns SettlementIdempotencyConflict when claimed key's settlement was never persisted`() {
        val existingId = UUID.randomUUID()
        whenever(settlementIdempotencyStore.tryRecord(any(), any(), any())).thenReturn(false)
        whenever(settlementIdempotencyStore.find("idem-key-001", tenantId)).thenReturn(existingId)
        whenever(settlementRepository.findById(existingId, tenantId)).thenReturn(null)

        val result = service.execute(cmd())

        assertTrue(result.isFailure)
        assertIs<SettlementIdempotencyConflict>(result.exceptionOrNull())
    }
}
