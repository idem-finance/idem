package finance.idem.application.compliance

import finance.idem.core.compliance.TravelRuleData
import finance.idem.core.monetary.OnChainEntry

sealed class TravelRuleValidationResult {
    object Exempt : TravelRuleValidationResult()

    data class Valid(
        val travelRuleData: TravelRuleData,
    ) : TravelRuleValidationResult()

    data class MissingData(
        val entry: OnChainEntry,
        val reason: String,
    ) : TravelRuleValidationResult()

    data class IncompleteData(
        val entry: OnChainEntry,
        val missingFields: List<String>,
    ) : TravelRuleValidationResult()
}
