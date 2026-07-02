package finance.idem.application.compliance

import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.compliance.LegalPerson
import finance.idem.core.compliance.NaturalPerson
import finance.idem.core.compliance.TravelRuleData
import finance.idem.core.compliance.VaspTransferParty
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TravelRuleValidatorTest {
    private val validator = TravelRuleValidator()

    private val originator =
        VaspTransferParty(
            naturalPerson =
                NaturalPerson(
                    firstName = "Jane",
                    lastName = "Doe",
                    dateOfBirth = LocalDate.of(1990, 1, 1),
                    country = "BR",
                ),
            accountNumber = "0xAbCd",
            vaspDid = "did:example:originator",
        )

    private val beneficiary =
        VaspTransferParty(
            legalPerson =
                LegalPerson(
                    name = "Acme Corp",
                    registrationNumber = "12.345.678/0001-90",
                    country = "US",
                ),
            accountNumber = "0xDeFg",
            vaspDid = "did:example:beneficiary",
        )

    private fun entry(amount: String) =
        OnChainEntry(
            amount = MonetaryAmount.of(amount),
            token = StablecoinToken.USDC,
            chainId = ChainId.EVM,
            txHash = "0xabc123",
            blockNumber = 1L,
            walletAddress = "0xwallet",
            tokenContract = "0xcontract",
        )

    private fun travelRuleData(
        amount: String = "1500",
        transferId: String = "tx-001",
        orig: VaspTransferParty = originator,
        bene: VaspTransferParty = beneficiary,
        threshold: MonetaryAmount = TravelRuleData.defaultThresholdFor(StablecoinToken.USDC),
    ) = TravelRuleData(
        transferId = transferId,
        originator = orig,
        beneficiary = bene,
        transferAmount = MonetaryAmount.of(amount),
        transferAsset = StablecoinToken.USDC,
        threshold = threshold,
    )

    // ---- Exempt ----

    @Test
    fun `Exempt when amount is below default threshold and travelRuleData is null`() {
        val result = validator.validate(entry("999"), null)
        assertIs<TravelRuleValidationResult.Exempt>(result)
    }

    @Test
    fun `Exempt when amount is well below default threshold`() {
        val result = validator.validate(entry("1"), null)
        assertIs<TravelRuleValidationResult.Exempt>(result)
    }

    @Test
    fun `Exempt when amount is below custom threshold carried in travelRuleData`() {
        val data = travelRuleData(amount = "200", threshold = MonetaryAmount.of("2000"))
        val result = validator.validate(entry("500"), data)
        assertIs<TravelRuleValidationResult.Exempt>(result)
    }

    // ---- MissingData ----

    @Test
    fun `MissingData when amount meets default threshold and travelRuleData is null`() {
        val result = validator.validate(entry("1000"), null)
        val missing = assertIs<TravelRuleValidationResult.MissingData>(result)
        assertTrue(missing.reason.isNotBlank())
        assertEquals(entry("1000"), missing.entry)
    }

    @Test
    fun `MissingData when amount exceeds default threshold and travelRuleData is null`() {
        val result = validator.validate(entry("5000"), null)
        assertIs<TravelRuleValidationResult.MissingData>(result)
    }

    @Test
    fun `MissingData reason mentions the threshold value`() {
        val result = validator.validate(entry("2000"), null)
        val missing = assertIs<TravelRuleValidationResult.MissingData>(result)
        assertTrue(missing.reason.contains("1000"), "Reason should mention the threshold: ${missing.reason}")
    }

    // ---- Valid ----

    @Test
    fun `Valid when travelRuleData is present and all fields are complete`() {
        val data = travelRuleData()
        val result = validator.validate(entry("1500"), data)
        val valid = assertIs<TravelRuleValidationResult.Valid>(result)
        assertEquals(data, valid.travelRuleData)
    }

    @Test
    fun `Valid when amount equals threshold exactly and travelRuleData is complete`() {
        val result = validator.validate(entry("1000"), travelRuleData(amount = "1000"))
        assertIs<TravelRuleValidationResult.Valid>(result)
    }

    @Test
    fun `Valid with natural person originator and legal person beneficiary`() {
        val result = validator.validate(entry("1500"), travelRuleData())
        assertIs<TravelRuleValidationResult.Valid>(result)
    }

    @Test
    fun `Valid with legal person originator and natural person beneficiary`() {
        val legalOriginator =
            VaspTransferParty(
                legalPerson = LegalPerson("PayCorp", "BR-999", "BR"),
                accountNumber = "0xPay",
                vaspDid = "did:example:paycorp",
            )
        val naturalBeneficiary =
            VaspTransferParty(
                naturalPerson = NaturalPerson("John", "Smith", LocalDate.of(1985, 3, 20), country = "US"),
                accountNumber = "0xJohn",
                vaspDid = "did:example:john",
            )
        val data = travelRuleData(orig = legalOriginator, bene = naturalBeneficiary)
        val result = validator.validate(entry("1500"), data)
        assertIs<TravelRuleValidationResult.Valid>(result)
    }

    // Note: IncompleteData is a defensive path. VaspTransferParty.init enforces non-blank vaspDid
    // and requires at least one of naturalPerson / legalPerson, so this result cannot be returned
    // by a properly-constructed domain object — there is no case for it above because validate()
    // has no reachable path that produces it. The sealed-class shape itself (missingFields, entry)
    // is exercised directly via ComplianceQueueItem.from(IncompleteData, tenantId) in
    // ComplianceQueueItemTest. It guards against future optional IVMS fields.
}
