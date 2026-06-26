package finance.idem.application.compliance

import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComplianceQueueItemTest {

    private val tenantId = TenantId.generate()

    private val entry = OnChainEntry(
        amount = MonetaryAmount.of("1500"),
        token = StablecoinToken.USDC,
        chainId = ChainId.EVM,
        txHash = "0xabc123",
        blockNumber = 42L,
        walletAddress = "0xwallet",
        tokenContract = "0xcontract",
    )

    @Test
    fun `from MissingData sets reason MISSING_DATA and empty missingFields`() {
        val result = TravelRuleValidationResult.MissingData(entry, "Travel rule data required")
        val item = ComplianceQueueItem.from(result, tenantId)

        assertNotNull(item.id)
        assertEquals(tenantId, item.tenantId)
        assertEquals("0xabc123", item.txHash)
        assertEquals(ChainId.EVM, item.chainId)
        assertEquals(MonetaryAmount.of("1500"), item.entryAmount)
        assertEquals("MISSING_DATA", item.reason)
        assertTrue(item.missingFields.isEmpty())
        assertNotNull(item.enqueuedAt)
    }

    @Test
    fun `from IncompleteData sets reason INCOMPLETE_DATA and preserves missingFields`() {
        val fields = listOf("originator.vaspDid", "beneficiary.vaspDid")
        val result = TravelRuleValidationResult.IncompleteData(entry, fields)
        val item = ComplianceQueueItem.from(result, tenantId)

        assertNotNull(item.id)
        assertEquals(tenantId, item.tenantId)
        assertEquals("0xabc123", item.txHash)
        assertEquals(ChainId.EVM, item.chainId)
        assertEquals("INCOMPLETE_DATA", item.reason)
        assertEquals(fields, item.missingFields)
    }

    @Test
    fun `each call to from MissingData generates a unique id`() {
        val result = TravelRuleValidationResult.MissingData(entry, "reason")
        val item1 = ComplianceQueueItem.from(result, tenantId)
        val item2 = ComplianceQueueItem.from(result, tenantId)
        assertTrue(item1.id != item2.id)
    }
}
