package finance.idem.application.settlement

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegisterSettlementUseCaseTest {
    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()

    private fun cmd(expectedFromAddress: String? = null) =
        RegisterSettlementCommand(
            tenantId = tenantId,
            accountId = accountId,
            amount = MonetaryAmount.of("100.00"),
            token = StablecoinToken.USDC,
            chainId = ChainId.SOLANA,
            walletAddress = "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS",
            expectedFromAddress = expectedFromAddress,
            createdBy = "api-user",
            idempotencyKey = "idem-key-001",
        )

    @Test
    fun `command carries all required fields`() {
        val c = cmd()
        assertEquals(tenantId, c.tenantId)
        assertEquals(accountId, c.accountId)
        assertEquals(MonetaryAmount.of("100.00"), c.amount)
        assertEquals(StablecoinToken.USDC, c.token)
        assertEquals(ChainId.SOLANA, c.chainId)
        assertEquals("5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS", c.walletAddress)
        assertNull(c.expectedFromAddress)
        assertEquals("api-user", c.createdBy)
        assertEquals("idem-key-001", c.idempotencyKey)
    }

    @Test
    fun `command carries optional expectedFromAddress when provided`() {
        val c = cmd(expectedFromAddress = "0xSender")
        assertNotNull(c.expectedFromAddress)
        assertEquals("0xSender", c.expectedFromAddress)
    }

    @Test
    fun `commands with same fields are equal`() {
        assertEquals(cmd(), cmd())
    }

    @Test
    fun `AccountNotFoundForSettlement holds accountId`() {
        val err = AccountNotFoundForSettlement(accountId)
        assertTrue(err.message!!.contains(accountId.value.toString()))
        assertEquals(accountId, err.accountId)
    }
}
