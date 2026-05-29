package finance.idem.core

import java.math.BigDecimal

class MonetaryAmount private constructor(val value: BigDecimal) {

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

    // Numeric equality: 0 == 0.00 == 0.000 (scale-insensitive)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MonetaryAmount) return false
        return value.compareTo(other.value) == 0
    }

    // Canonical scale so equal values produce identical hash codes
    override fun hashCode(): Int = value.setScale(MAX_SCALE).hashCode()

    override fun toString(): String = "MonetaryAmount($value)"

    companion object {
        const val MAX_SCALE = 18

        fun of(value: String): MonetaryAmount = MonetaryAmount(BigDecimal(value))

        fun of(value: Long): MonetaryAmount = MonetaryAmount(BigDecimal.valueOf(value))

        fun of(value: BigDecimal): MonetaryAmount = MonetaryAmount(value)

        val ZERO: MonetaryAmount = MonetaryAmount(BigDecimal.ZERO)
    }
}
