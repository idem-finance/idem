package finance.idem.core.compliance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class LegalPersonTest {
    @Test
    fun `blank name throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            LegalPerson(name = "  ", registrationNumber = "REG123", country = "BR")
        }
    }

    @Test
    fun `blank registrationNumber throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            LegalPerson(name = "Acme Corp", registrationNumber = "", country = "BR")
        }
    }

    @Test
    fun `country not 2 chars throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            LegalPerson(name = "Acme Corp", registrationNumber = "REG123", country = "USA")
        }
    }

    @Test
    fun `happy path round-trips all fields correctly`() {
        val entity =
            LegalPerson(
                name = "Acme Corp",
                registrationNumber = "12.345.678/0001-90",
                country = "BR",
            )
        assertEquals("Acme Corp", entity.name)
        assertEquals("12.345.678/0001-90", entity.registrationNumber)
        assertEquals("BR", entity.country)
    }
}
