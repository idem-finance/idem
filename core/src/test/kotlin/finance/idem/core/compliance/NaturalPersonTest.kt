package finance.idem.core.compliance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertEquals

class NaturalPersonTest {
    @Test
    fun `blank firstName throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            NaturalPerson(firstName = "  ", lastName = "Doe", dateOfBirth = LocalDate.of(1990, 1, 1), country = "BR")
        }
    }

    @Test
    fun `blank lastName throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            NaturalPerson(firstName = "Jane", lastName = "", dateOfBirth = LocalDate.of(1990, 1, 1), country = "BR")
        }
    }

    @Test
    fun `country not 2 chars throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            NaturalPerson(firstName = "Jane", lastName = "Doe", dateOfBirth = LocalDate.of(1990, 1, 1), country = "BRA")
        }
    }

    @Test
    fun `single-char country throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            NaturalPerson(firstName = "Jane", lastName = "Doe", dateOfBirth = LocalDate.of(1990, 1, 1), country = "B")
        }
    }

    @Test
    fun `happy path with all fields round-trips correctly`() {
        val person =
            NaturalPerson(
                firstName = "Jane",
                lastName = "Doe",
                dateOfBirth = LocalDate.of(1990, 6, 15),
                nationalId = "123456789",
                country = "BR",
            )
        assertEquals("Jane", person.firstName)
        assertEquals("Doe", person.lastName)
        assertEquals(LocalDate.of(1990, 6, 15), person.dateOfBirth)
        assertEquals("123456789", person.nationalId)
        assertEquals("BR", person.country)
    }

    @Test
    fun `happy path without nationalId defaults to null`() {
        val person =
            NaturalPerson(
                firstName = "John",
                lastName = "Smith",
                dateOfBirth = LocalDate.of(1985, 3, 20),
                country = "US",
            )
        assertEquals(null, person.nationalId)
    }
}
