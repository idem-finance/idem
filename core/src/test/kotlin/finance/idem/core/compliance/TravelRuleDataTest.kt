package finance.idem.core.compliance

import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TravelRuleDataTest {
    private val party =
        VaspTransferParty(
            naturalPerson =
                NaturalPerson(
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
        val data =
            TravelRuleData(
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
        val data =
            TravelRuleData(
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
        val data =
            TravelRuleData(
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
        val data =
            TravelRuleData(
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
    fun `BRZ default threshold is 5500 — not 1000 — so 1001 BRZ does not incorrectly trigger`() {
        val data =
            TravelRuleData(
                transferId = "tx-brz-001",
                originator = party,
                beneficiary = party,
                transferAmount = MonetaryAmount.of("1001"),
                transferAsset = StablecoinToken.BRZ,
            )
        // 1001 BRZ ~$182 USD — must NOT trigger the $1,000 equivalent threshold
        assertFalse(data.isAboveThreshold())
        assertEquals(MonetaryAmount.of("5500"), data.threshold)
    }

    @Test
    fun `BRZ transfer above 5500 triggers threshold`() {
        val data =
            TravelRuleData(
                transferId = "tx-brz-002",
                originator = party,
                beneficiary = party,
                transferAmount = MonetaryAmount.of("5500"),
                transferAsset = StablecoinToken.BRZ,
            )
        assertTrue(data.isAboveThreshold())
    }

    @Test
    fun `defaultThresholdFor returns 1000 for USD-pegged tokens`() {
        assertEquals(MonetaryAmount.of("1000"), TravelRuleData.defaultThresholdFor(StablecoinToken.USDC))
        assertEquals(MonetaryAmount.of("1000"), TravelRuleData.defaultThresholdFor(StablecoinToken.USDT))
        assertEquals(MonetaryAmount.of("1000"), TravelRuleData.defaultThresholdFor(StablecoinToken.PYUSD))
    }

    @Test
    fun `defaultThresholdFor returns 5500 for BRZ`() {
        assertEquals(MonetaryAmount.of("5500"), TravelRuleData.defaultThresholdFor(StablecoinToken.BRZ))
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
