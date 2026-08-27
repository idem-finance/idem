package finance.idem.core.ledger

import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken

data class OnChainBalance(
    val token: StablecoinToken,
    val amount: MonetaryAmount,
    // Portion of `amount` still WATCHING an unconfirmed on-chain credit — not yet past its
    // chain's finality bound, so it could still be reversed by a reorg. Callers that act on
    // funds (e.g. an agent deciding whether to disburse) should treat amount - pendingFinalityAmount
    // as the safe, reorg-proof figure. See SettlementFinalityPoller / ReorgReversalService.
    val pendingFinalityAmount: MonetaryAmount = MonetaryAmount.ZERO,
)
