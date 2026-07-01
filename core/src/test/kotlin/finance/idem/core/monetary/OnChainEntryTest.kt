package finance.idem.core.monetary

import finance.idem.core.ChainId
import finance.idem.core.LedgerInvariantViolation
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.compliance.NaturalPerson
import finance.idem.core.compliance.TravelRuleData
import finance.idem.core.compliance.VaspTransferParty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnChainEntryTest {
    private val validEntry =
        OnChainEntry(
            amount = MonetaryAmount.of("100"),
            token = StablecoinToken.USDC,
            chainId = ChainId.EVM,
            txHash = "0xdeadbeef",
            blockNumber = 1_000_000L,
            walletAddress = "0xRecipient",
            tokenContract = "0xTokenContract",
        )

    @Test
    fun `valid entry without travelRuleData constructs successfully`() {
        assertNull(validEntry.travelRuleData)
    }

    @Test
    fun `valid entry with travelRuleData attaches the field`() {
        val party =
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
        val travelRule =
            TravelRuleData(
                transferId = "tx-001",
                originator = party,
                beneficiary = party,
                transferAmount = MonetaryAmount.of("1500"),
                transferAsset = StablecoinToken.USDC,
            )

        val entry = validEntry.copy(travelRuleData = travelRule)

        assertEquals(travelRule, entry.travelRuleData)
        assertEquals("tx-001", entry.travelRuleData?.transferId)
    }

    @Test
    fun `non-positive amount throws LedgerInvariantViolation`() {
        assertThrows<LedgerInvariantViolation> {
            validEntry.copy(amount = MonetaryAmount.ZERO)
        }
    }

    @Test
    fun `blank txHash throws LedgerInvariantViolation`() {
        assertThrows<LedgerInvariantViolation> {
            validEntry.copy(txHash = "  ")
        }
    }

    @Test
    fun `blank walletAddress throws LedgerInvariantViolation`() {
        assertThrows<LedgerInvariantViolation> {
            validEntry.copy(walletAddress = "")
        }
    }

    @Test
    fun `blank tokenContract throws LedgerInvariantViolation`() {
        assertThrows<LedgerInvariantViolation> {
            validEntry.copy(tokenContract = "")
        }
    }
}
