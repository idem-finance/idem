package finance.idem.mcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AccountDescriptionResultTest {
    @Test
    fun `onChainBalances defaults to empty list when omitted from the constructor`() {
        val result =
            AccountDescriptionResult(
                accountId = "acc-1",
                name = "Nostro",
                description = null,
                currency = "USD",
                entryCount = 3,
                lastActivityAt = "2026-01-01T00:00:00Z",
                balanceCurrency = "USD",
                balanceAmount = "100",
            )
        assertEquals(emptyList(), result.onChainBalances)
    }

    @Test
    fun `holds all fields including on-chain breakdown`() {
        val tokenBalance = OnChainTokenBalance(token = "USDC", amount = "2.50", pendingFinalityAmount = "0.50")
        val result =
            AccountDescriptionResult(
                accountId = "acc-1",
                name = "Nostro",
                description = "Custody account",
                currency = "USD",
                entryCount = 3,
                lastActivityAt = "2026-01-01T00:00:00Z",
                balanceCurrency = "USD",
                balanceAmount = "100",
                onChainBalances = listOf(tokenBalance),
            )

        assertEquals("acc-1", result.accountId)
        assertEquals("Nostro", result.name)
        assertEquals("Custody account", result.description)
        assertEquals(listOf(tokenBalance), result.onChainBalances)
        assertEquals(result, result.copy())
    }
}
