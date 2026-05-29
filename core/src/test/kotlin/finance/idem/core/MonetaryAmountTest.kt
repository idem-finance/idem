package finance.idem.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonetaryAmountTest {

    @Test
    fun `plus adds two amounts`() {
        val result = MonetaryAmount.of("100.50") + MonetaryAmount.of("49.50")
        assertEquals(MonetaryAmount.of("150.00"), result)
    }

    @Test
    fun `minus subtracts two amounts`() {
        val result = MonetaryAmount.of("100.00") - MonetaryAmount.of("40.00")
        assertEquals(MonetaryAmount.of("60.00"), result)
    }

    @Test
    fun `isZero returns true for zero value`() {
        assertTrue(MonetaryAmount.ZERO.isZero())
        assertTrue(MonetaryAmount.of("0.00").isZero())
    }

    @Test
    fun `isZero returns false for non-zero value`() {
        assertFalse(MonetaryAmount.of("0.01").isZero())
    }

    @Test
    fun `isPositive returns true for positive value`() {
        assertTrue(MonetaryAmount.of("1.00").isPositive())
    }

    @Test
    fun `isPositive returns false for zero`() {
        assertFalse(MonetaryAmount.ZERO.isPositive())
    }

    @Test
    fun `scale exceeding 18 is rejected`() {
        val tooManyDecimals = BigDecimal("0.1234567890123456789") // 19 decimal places
        assertThrows<IllegalArgumentException> {
            MonetaryAmount(tooManyDecimals)
        }
    }

    @Test
    fun `scale of exactly 18 is accepted`() {
        val maxScale = BigDecimal("0.123456789012345678") // 18 decimal places
        val amount = MonetaryAmount(maxScale)
        assertEquals(18, amount.value.scale())
    }

    @Test
    fun `equality is based on value`() {
        assertEquals(MonetaryAmount.of("1.00"), MonetaryAmount.of("1.00"))
    }

    @Test
    fun `compareTo works correctly`() {
        val small = MonetaryAmount.of("1.00")
        val large = MonetaryAmount.of("2.00")
        assertTrue(small < large)
        assertTrue(large > small)
    }
}
