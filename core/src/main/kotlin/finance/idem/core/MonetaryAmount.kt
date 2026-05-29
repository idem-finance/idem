package finance.idem.core

import java.math.BigDecimal
import java.math.RoundingMode

data class MonetaryAmount(val value: BigDecimal) {

    init {
        require(value.scale() <= MAX_SCALE) {
            "MonetaryAmount scale ${value.scale()} exceeds maximum $MAX_SCALE"
        }
    }

    operator fun plus(other: MonetaryAmount): MonetaryAmount =
        MonetaryAmount(value.add(other.value))

    operator fun minus(other: MonetaryAmount): MonetaryAmount =
        MonetaryAmount(value.subtract(other.value))

    fun isZero(): Boolean = value.compareTo(BigDecimal.ZERO) == 0

    fun isPositive(): Boolean = value > BigDecimal.ZERO

    operator fun compareTo(other: MonetaryAmount): Int = value.compareTo(other.value)

    companion object {
        const val MAX_SCALE = 18

        fun of(value: String): MonetaryAmount = MonetaryAmount(BigDecimal(value))

        fun of(value: Long): MonetaryAmount = MonetaryAmount(BigDecimal.valueOf(value))

        fun of(value: BigDecimal): MonetaryAmount = MonetaryAmount(value)

        val ZERO: MonetaryAmount = MonetaryAmount(BigDecimal.ZERO)
    }
}
