package finance.idem.mcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BalanceResultTest {
    @Test
    fun `onChainBalances defaults to empty list when omitted from the constructor`() {
        val result = BalanceResult(accountId = "acc-1", currency = "USD", amount = "0", computedAt = "2026-01-01T00:00:00Z")
        assertEquals(emptyList(), result.onChainBalances)
    }

    @Test
    fun `holds all fields including on-chain breakdown`() {
        val tokenBalance = OnChainTokenBalance(token = "USDC", amount = "2.50", pendingFinalityAmount = "0.50")
        val result =
            BalanceResult(
                accountId = "acc-1",
                currency = "USD",
                amount = "0",
                computedAt = "2026-01-01T00:00:00Z",
                onChainBalances = listOf(tokenBalance),
            )

        assertEquals("acc-1", result.accountId)
        assertEquals(listOf(tokenBalance), result.onChainBalances)
        assertEquals("USDC", tokenBalance.token)
        assertEquals("2.50", tokenBalance.amount)
        assertEquals("0.50", tokenBalance.pendingFinalityAmount)
        assertEquals(tokenBalance, tokenBalance.copy())
    }
}
