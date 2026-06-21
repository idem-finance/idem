package finance.idem.core.agentic

import finance.idem.core.MonetaryAmount

/**
 * The complete description of a ledger operation that an agent intends to perform.
 *
 * [PolicyGuard] evaluates [lines] against a set of [PolicyRule]s. For rules that
 * involve historical totals ([PolicyRule.MaxDebitPerSession], [PolicyRule.MaxDebitPerHour]),
 * the caller is responsible for pre-computing [priorSessionDebitTotal] and
 * [priorHourlyDebitTotal] from historical journal data before constructing this intent.
 *
 * All [MonetaryAmount] values — across [lines], [priorSessionDebitTotal], and
 * [priorHourlyDebitTotal] — must be normalized to the same unit by the caller.
 * [PolicyGuard] performs raw BigDecimal addition with no currency conversion.
 */
data class LedgerIntent(
    val lines: List<LedgerIntentLine>,
    val priorSessionDebitTotal: MonetaryAmount = MonetaryAmount.ZERO,
    val priorHourlyDebitTotal: MonetaryAmount = MonetaryAmount.ZERO,
)
