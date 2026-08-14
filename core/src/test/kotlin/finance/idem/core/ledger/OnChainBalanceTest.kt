package finance.idem.core.ledger

import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OnChainBalanceTest {
    @Test
    fun `equality is based on all fields`() {
        val a = OnChainBalance(StablecoinToken.USDC, MonetaryAmount.of("100"))
        val b = OnChainBalance(StablecoinToken.USDC, MonetaryAmount.of("100"))
        assertEquals(a, b)
    }

    @Test
    fun `different tokens are not equal`() {
        assertNotEquals(
            OnChainBalance(StablecoinToken.USDC, MonetaryAmount.of("100")),
            OnChainBalance(StablecoinToken.USDT, MonetaryAmount.of("100")),
        )
    }

    @Test
    fun `copy with updated amount reflects new value`() {
        val original = OnChainBalance(StablecoinToken.BRZ, MonetaryAmount.of("10"))
        val updated = original.copy(amount = MonetaryAmount.of("25"))
        assertEquals(StablecoinToken.BRZ, updated.token)
        assertEquals(MonetaryAmount.of("25"), updated.amount)
    }
}
