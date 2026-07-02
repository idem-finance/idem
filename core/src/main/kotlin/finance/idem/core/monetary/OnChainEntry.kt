package finance.idem.core.monetary

import finance.idem.core.ChainId
import finance.idem.core.LedgerInvariantViolation
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.compliance.TravelRuleData

data class OnChainEntry(
    override val amount: MonetaryAmount,
    val token: StablecoinToken,
    val chainId: ChainId,
    val txHash: String,
    val blockNumber: Long,
    val walletAddress: String,
    val tokenContract: String,
    val fromAddress: String? = null,
    val travelRuleData: TravelRuleData? = null,
) : MonetaryEntry() {
    init {
        if (!amount.isPositive()) {
            throw LedgerInvariantViolation(
                "OnChainEntry amount must be positive, got ${amount.value}",
            )
        }
        if (txHash.isBlank()) throw LedgerInvariantViolation("OnChainEntry txHash must not be blank")
        if (walletAddress.isBlank()) throw LedgerInvariantViolation("OnChainEntry walletAddress must not be blank")
        if (tokenContract.isBlank()) throw LedgerInvariantViolation("OnChainEntry tokenContract must not be blank")
    }
}
