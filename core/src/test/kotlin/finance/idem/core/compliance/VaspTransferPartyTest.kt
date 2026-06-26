package finance.idem.core.compliance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VaspTransferPartyTest {

    private val naturalPerson = NaturalPerson(
        firstName = "Jane",
        lastName = "Doe",
        dateOfBirth = LocalDate.of(1990, 1, 1),
        country = "BR",
    )

    private val legalPerson = LegalPerson(
        name = "Acme Corp",
        registrationNumber = "REG123",
        country = "US",
    )

    @Test
    fun `both naturalPerson and legalPerson null throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            VaspTransferParty(naturalPerson = null, legalPerson = null, accountNumber = "0x1234", vaspDid = "did:example:1")
        }
    }

    @Test
    fun `both naturalPerson and legalPerson set throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            VaspTransferParty(
                naturalPerson = naturalPerson,
                legalPerson = legalPerson,
                accountNumber = "0x1234",
                vaspDid = "did:example:1",
            )
        }
    }

    @Test
    fun `blank accountNumber throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            VaspTransferParty(naturalPerson = naturalPerson, accountNumber = "  ", vaspDid = "did:example:1")
        }
    }

    @Test
    fun `blank vaspDid throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            VaspTransferParty(naturalPerson = naturalPerson, accountNumber = "0x1234", vaspDid = "")
        }
    }

    @Test
    fun `happy path with naturalPerson`() {
        val party = VaspTransferParty(
            naturalPerson = naturalPerson,
            accountNumber = "0xAbCd1234",
            vaspDid = "did:example:originator",
        )
        assertNotNull(party.naturalPerson)
        assertEquals(null, party.legalPerson)
        assertEquals("0xAbCd1234", party.accountNumber)
        assertEquals("did:example:originator", party.vaspDid)
    }

    @Test
    fun `happy path with legalPerson`() {
        val party = VaspTransferParty(
            legalPerson = legalPerson,
            accountNumber = "GB29NWBK60161331926819",
            vaspDid = "did:example:beneficiary-vasp",
        )
        assertEquals(null, party.naturalPerson)
        assertNotNull(party.legalPerson)
        assertEquals("GB29NWBK60161331926819", party.accountNumber)
    }
}
