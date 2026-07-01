package finance.idem.core.compliance

data class LegalPerson(
    @PiiField(PiiCategory.FULL_NAME) val name: String,
    @PiiField(PiiCategory.DOCUMENT_NUMBER) val registrationNumber: String,
    val country: String,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(registrationNumber.isNotBlank()) { "registrationNumber must not be blank" }
        require(country.length == 2) { "country must be ISO 3166-1 alpha-2" }
    }
}
