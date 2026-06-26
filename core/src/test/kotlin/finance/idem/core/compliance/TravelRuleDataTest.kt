package finance.idem.core.compliance

import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TravelRuleDataTest {

    private val party = VaspTransferParty(
        naturalPerson = NaturalPerson(
            firstName = "Jane",
            lastName = "Doe",
            dateOfBirth = LocalDate.of(1990, 1, 1),
            country = "BR",
        ),
        accountNumber = "0xAbCd",
        vaspDid = "did:example:vasp",
    )

    @Test
    fun `isAboveThreshold returns true when amount exceeds default threshold`() {
        val data = TravelRuleData(
            transferId = "tx-001",
            originator = party,
            beneficiary = party,
            transferAmount = MonetaryAmount.of("1500"),
            transferAsset = StablecoinToken.USDC,
        )
        assertTrue(data.isAboveThreshold())
    }

    @Test
    fun `isAboveThreshold returns false when amount is below default threshold`() {
        val data = TravelRuleData(
            transferId = "tx-002",
            originator = party,
            beneficiary = party,
            transferAmount = MonetaryAmount.of("999"),
            transferAsset = StablecoinToken.USDC,
        )
        assertFalse(data.isAboveThreshold())
    }

    @Test
    fun `isAboveThreshold returns true when amount equals threshold`() {
        val data = TravelRuleData(
            transferId = "tx-003",
            originator = party,
            beneficiary = party,
            transferAmount = MonetaryAmount.of("1000"),
            transferAsset = StablecoinToken.USDC,
        )
        assertTrue(data.isAboveThreshold())
    }

    @Test
    fun `custom threshold overrides default`() {
        val data = TravelRuleData(
            transferId = "tx-004",
            originator = party,
            beneficiary = party,
            transferAmount = MonetaryAmount.of("500"),
            transferAsset = StablecoinToken.USDT,
            threshold = MonetaryAmount.of("200"),
        )
        assertTrue(data.isAboveThreshold())
    }

    @Test
    fun `blank transferId throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            TravelRuleData(
                transferId = "  ",
                originator = party,
                beneficiary = party,
                transferAmount = MonetaryAmount.of("1000"),
                transferAsset = StablecoinToken.USDC,
            )
        }
    }

    @Test
    fun `zero transferAmount throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            TravelRuleData(
                transferId = "tx-005",
                originator = party,
                beneficiary = party,
                transferAmount = MonetaryAmount.ZERO,
                transferAsset = StablecoinToken.USDC,
            )
        }
    }
}
