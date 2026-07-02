package finance.idem.core.compliance

import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken

/**
 * Travel Rule compliance payload (IVMS 101).
 *
 * `threshold` must be in the same units as `transferAmount` — it is a token-denominated amount,
 * not a USD amount. Use [defaultThresholdFor] to obtain the per-asset equivalent of the FATF
 * $1,000 USD threshold. Callers that supply a custom threshold are responsible for keeping it
 * in the same denomination as the transfer asset.
 */
data class TravelRuleData(
    val transferId: String,
    val originator: VaspTransferParty,
    val beneficiary: VaspTransferParty,
    val transferAmount: MonetaryAmount,
    val transferAsset: StablecoinToken,
    val threshold: MonetaryAmount = defaultThresholdFor(transferAsset),
) {
    companion object {
        /**
         * Returns the FATF $1,000 USD threshold expressed in native token units for [asset].
         * BRZ values are reviewed quarterly against the BRL/USD rate.
         */
        fun defaultThresholdFor(asset: StablecoinToken): MonetaryAmount =
            when (asset) {
                StablecoinToken.USDC,
                StablecoinToken.USDT,
                StablecoinToken.PYUSD,
                -> MonetaryAmount.of("1000")

                StablecoinToken.BRZ -> MonetaryAmount.of("5500") // ~$1,000 USD at BRL/USD 5.5
            }
    }

    init {
        require(transferId.isNotBlank()) { "transferId must not be blank" }
        require(transferAmount > MonetaryAmount.ZERO) { "transferAmount must be positive" }
        require(threshold > MonetaryAmount.ZERO) { "threshold must be positive" }
    }

    fun isAboveThreshold(): Boolean = transferAmount >= threshold
}
