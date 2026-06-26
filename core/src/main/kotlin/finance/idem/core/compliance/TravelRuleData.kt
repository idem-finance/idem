package finance.idem.core.compliance

import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken

data class TravelRuleData(
    val transferId: String,
    val originator: VaspTransferParty,
    val beneficiary: VaspTransferParty,
    val transferAmount: MonetaryAmount,
    val transferAsset: StablecoinToken,
    val threshold: MonetaryAmount = DEFAULT_THRESHOLD,
) {
    companion object {
        val DEFAULT_THRESHOLD: MonetaryAmount = MonetaryAmount.of("1000")
    }

    init {
        require(transferId.isNotBlank())              { "transferId must not be blank" }
        require(transferAmount > MonetaryAmount.ZERO) { "transferAmount must be positive" }
        require(threshold > MonetaryAmount.ZERO)      { "threshold must be positive" }
    }

    fun isAboveThreshold(): Boolean = transferAmount >= threshold
}
