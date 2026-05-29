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
            MonetaryAmount.of(tooManyDecimals)
        }
    }

    @Test
    fun `scale of exactly 18 is accepted`() {
        val maxScale = BigDecimal("0.123456789012345678") // 18 decimal places
        val amount = MonetaryAmount.of(maxScale)
        assertEquals(18, amount.value.scale())
    }

    @Test
    fun `of(Long) creates amount from long value`() {
        val amount = MonetaryAmount.of(100L)
        assertEquals(MonetaryAmount.of("100"), amount)
        assertTrue(amount.isPositive())
    }

    @Test
    fun `toString includes the value`() {
        val amount = MonetaryAmount.of("42.50")
        assertTrue(amount.toString().contains("42.50"))
    }

    @Test
    fun `equality is numeric — different scales are equal`() {
        assertEquals(MonetaryAmount.of("0"), MonetaryAmount.of("0.00"))
        assertEquals(MonetaryAmount.ZERO, MonetaryAmount.of("0.00"))
        assertEquals(MonetaryAmount.of("1"), MonetaryAmount.of("1.00"))
        assertEquals(MonetaryAmount.of("1.5"), MonetaryAmount.of("1.50"))
    }

    @Test
    fun `hashCode is consistent across scales`() {
        assertEquals(MonetaryAmount.of("0").hashCode(), MonetaryAmount.of("0.00").hashCode())
        assertEquals(MonetaryAmount.ZERO.hashCode(), MonetaryAmount.of("0.000").hashCode())
        assertEquals(MonetaryAmount.of("1.5").hashCode(), MonetaryAmount.of("1.50").hashCode())
    }

    @Test
    fun `equal amounts behave correctly as map keys`() {
        val map = hashMapOf(MonetaryAmount.of("1.00") to "one")
        assertEquals("one", map[MonetaryAmount.of("1")])
        assertEquals("one", map[MonetaryAmount.of("1.0")])
    }

    @Test
    fun `compareTo works correctly`() {
        val small = MonetaryAmount.of("1.00")
        val large = MonetaryAmount.of("2.00")
        assertTrue(small < large)
        assertTrue(large > small)
    }
}
