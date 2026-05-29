package finance.idem.core.monetary

import finance.idem.core.ChainId
import finance.idem.core.FiatCurrency
import finance.idem.core.LedgerInvariantViolation
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken

sealed class MonetaryEntry {

    abstract val amount: MonetaryAmount

    data class FiatEntry(
        override val amount: MonetaryAmount,
        val currency: FiatCurrency,
        val rail: PaymentRail,
        val bankReference: String? = null,
    ) : MonetaryEntry() {
        init {
            if (!amount.isPositive()) throw LedgerInvariantViolation(
                "FiatEntry amount must be positive, got ${amount.value}"
            )
        }
    }

    data class OnChainEntry(
        override val amount: MonetaryAmount,
        val token: StablecoinToken,
        val chainId: ChainId,
        val txHash: String,
        val blockNumber: Long,
        val walletAddress: String,
        val tokenContract: String,
    ) : MonetaryEntry() {
        init {
            if (!amount.isPositive()) throw LedgerInvariantViolation(
                "OnChainEntry amount must be positive, got ${amount.value}"
            )
            if (txHash.isBlank()) throw LedgerInvariantViolation("OnChainEntry txHash must not be blank")
            if (walletAddress.isBlank()) throw LedgerInvariantViolation("OnChainEntry walletAddress must not be blank")
            if (tokenContract.isBlank()) throw LedgerInvariantViolation("OnChainEntry tokenContract must not be blank")
        }
    }
}
