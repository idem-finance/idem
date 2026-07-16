package finance.idem.infrastructure.persistence.chain

import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.chain.FailedChainTransfer
import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FailedChainTransferRepositoryAdapter::class)
class FailedChainTransferRepositoryAdapterTest : SharedPostgresTestBase() {
    @Autowired lateinit var adapter: FailedChainTransferRepositoryAdapter

    @Autowired lateinit var jpaRepository: FailedChainTransferJpaRepository

    private val tenantId = TenantId.generate()
    private val debitAccountId = UUID.randomUUID()
    private val creditAccountId = UUID.randomUUID()

    private fun transfer(
        idempotencyKey: String,
        txHash: String = "0xabc",
        errorMessage: String = "conflict",
    ) = FailedChainTransfer(
        id = UUID.randomUUID(),
        chainKey = "EVM_1",
        source = "chain-recovery",
        idempotencyKey = idempotencyKey,
        txHash = txHash,
        blockNumber = 100L,
        tenantId = tenantId,
        walletAddress = "0xwallet",
        tokenContract = "0xcontract",
        debitAccountId = debitAccountId,
        creditAccountId = creditAccountId,
        token = StablecoinToken.USDC,
        amount = MonetaryAmount.of("100.000000"),
        errorMessage = errorMessage,
        createdAt = Instant.now(),
    )

    @Test
    fun `save persists all fields and defaults resolved to false`() {
        val xfer = transfer(idempotencyKey = "EVM_1:0xabc")

        adapter.save(xfer)

        val row = jpaRepository.findById(xfer.id).orElseThrow()
        assertEquals("EVM_1", row.chainKey)
        assertEquals("chain-recovery", row.source)
        assertEquals("EVM_1:0xabc", row.idempotencyKey)
        assertEquals("0xabc", row.txHash)
        assertEquals(100L, row.blockNumber)
        assertEquals(tenantId.value, row.tenantId)
        assertEquals("0xwallet", row.walletAddress)
        assertEquals("0xcontract", row.tokenContract)
        assertEquals(debitAccountId, row.debitAccountId)
        assertEquals(creditAccountId, row.creditAccountId)
        assertEquals(StablecoinToken.USDC, row.token)
        assertEquals("conflict", row.errorMessage)
        assertFalse(row.resolved)
    }

    @Test
    fun `save is idempotent — duplicate idempotencyKey does not insert a second row`() {
        val first = transfer(idempotencyKey = "EVM_1:0xdup", errorMessage = "first failure")
        val retry = transfer(idempotencyKey = "EVM_1:0xdup", errorMessage = "retry failure")

        adapter.save(first)
        adapter.save(retry)

        val rows = jpaRepository.findAll().filter { it.idempotencyKey == "EVM_1:0xdup" }
        assertEquals(1, rows.size)
        assertEquals("first failure", rows[0].errorMessage)
    }

    @Test
    fun `independent idempotencyKeys are each persisted`() {
        adapter.save(transfer(idempotencyKey = "EVM_1:0x1"))
        adapter.save(transfer(idempotencyKey = "EVM_1:0x2"))

        val keys = jpaRepository.findAll().map { it.idempotencyKey }
        assertTrue(keys.containsAll(listOf("EVM_1:0x1", "EVM_1:0x2")))
    }
}
