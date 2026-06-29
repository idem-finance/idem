package finance.idem.core.compliance

import java.time.LocalDate

data class NaturalPerson(
    @PiiField(PiiCategory.FULL_NAME)       val firstName: String,
    @PiiField(PiiCategory.FULL_NAME)       val lastName: String,
    @PiiField(PiiCategory.DATE_OF_BIRTH)   val dateOfBirth: LocalDate,
    @PiiField(PiiCategory.DOCUMENT_NUMBER) val nationalId: String? = null,
    val country: String,
) {
    init {
        require(firstName.isNotBlank()) { "firstName must not be blank" }
        require(lastName.isNotBlank())  { "lastName must not be blank" }
        require(country.length == 2)    { "country must be ISO 3166-1 alpha-2" }
    }
}
