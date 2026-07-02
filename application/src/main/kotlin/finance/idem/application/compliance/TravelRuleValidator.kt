package finance.idem.application.compliance

import finance.idem.core.compliance.TravelRuleData
import finance.idem.core.compliance.VaspTransferParty
import finance.idem.core.monetary.OnChainEntry

class TravelRuleValidator {
    fun validate(
        entry: OnChainEntry,
        travelRuleData: TravelRuleData?,
    ): TravelRuleValidationResult {
        val threshold = travelRuleData?.threshold ?: TravelRuleData.defaultThresholdFor(entry.token)

        if (entry.amount < threshold) return TravelRuleValidationResult.Exempt

        if (travelRuleData == null) {
            return TravelRuleValidationResult.MissingData(
                entry = entry,
                reason = "Travel rule data required for transfers >= $threshold",
            )
        }

        val missingFields =
            missingFieldsOf("originator", travelRuleData.originator) +
                missingFieldsOf("beneficiary", travelRuleData.beneficiary)
        if (missingFields.isNotEmpty()) {
            return TravelRuleValidationResult.IncompleteData(entry = entry, missingFields = missingFields)
        }

        return TravelRuleValidationResult.Valid(travelRuleData)
    }

    // VaspTransferParty.init already enforces non-blank vaspDid/accountNumber and exactly one
    // of naturalPerson / legalPerson. LegalPerson requires non-blank name/registrationNumber
    // at construction, so it can never be incomplete. NaturalPerson.nationalId is the one
    // IVMS 101 field the domain model allows to be omitted — a natural person party without
    // it is the reachable "incomplete" case.
    private fun missingFieldsOf(
        role: String,
        party: VaspTransferParty,
    ): List<String> {
        val naturalPerson = party.naturalPerson ?: return emptyList()
        return if (naturalPerson.nationalId.isNullOrBlank()) listOf("$role.naturalPerson.nationalId") else emptyList()
    }
}
