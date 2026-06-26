package finance.idem.core.compliance

import java.time.LocalDate

data class NaturalPerson(
    val firstName: String,
    val lastName: String,
    val dateOfBirth: LocalDate,
    val nationalId: String? = null,
    val country: String,
) {
    init {
        require(firstName.isNotBlank()) { "firstName must not be blank" }
        require(lastName.isNotBlank())  { "lastName must not be blank" }
        require(country.length == 2)    { "country must be ISO 3166-1 alpha-2" }
    }
}
