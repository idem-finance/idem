package finance.idem.core.compliance

data class VaspTransferParty(
    val naturalPerson: NaturalPerson? = null,
    val legalPerson: LegalPerson? = null,
    @PiiField(PiiCategory.FINANCIAL_DATA) val accountNumber: String,
    val vaspDid: String,
) {
    init {
        require(naturalPerson != null || legalPerson != null) {
            "VaspTransferParty requires either naturalPerson or legalPerson"
        }
        require(naturalPerson == null || legalPerson == null) {
            "VaspTransferParty cannot have both naturalPerson and legalPerson"
        }
        require(accountNumber.isNotBlank()) { "accountNumber must not be blank" }
        require(vaspDid.isNotBlank())       { "vaspDid must not be blank" }
    }
}
