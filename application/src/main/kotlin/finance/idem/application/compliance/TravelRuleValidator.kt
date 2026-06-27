package finance.idem.application.compliance

import finance.idem.core.compliance.TravelRuleData
import finance.idem.core.monetary.OnChainEntry

class TravelRuleValidator {

    fun validate(entry: OnChainEntry, travelRuleData: TravelRuleData?): TravelRuleValidationResult {
        val threshold = travelRuleData?.threshold ?: TravelRuleData.defaultThresholdFor(entry.token)

        if (entry.amount < threshold) return TravelRuleValidationResult.Exempt

        if (travelRuleData == null) {
            return TravelRuleValidationResult.MissingData(
                entry = entry,
                reason = "Travel rule data required for transfers >= $threshold",
            )
        }

        // VaspTransferParty.init enforces non-blank vaspDid and requires exactly one of
        // naturalPerson / legalPerson — so any validly-constructed TravelRuleData is always Valid here.
        // IncompleteData is reserved for future IVMS 101 extended-field checks.
        return TravelRuleValidationResult.Valid(travelRuleData)
    }
}
