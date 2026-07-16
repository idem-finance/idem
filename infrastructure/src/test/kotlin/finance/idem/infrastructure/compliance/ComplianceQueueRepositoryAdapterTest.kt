package finance.idem.infrastructure.compliance

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import finance.idem.application.compliance.ComplianceQueueItem
import finance.idem.application.compliance.TravelRuleValidationResult
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.monetary.OnChainEntry
import finance.idem.infrastructure.SharedPostgresTestBase
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ComplianceQueueRepositoryAdapter::class, PersistenceTestConfig::class)
class ComplianceQueueRepositoryAdapterTest : SharedPostgresTestBase() {
    @Autowired
    lateinit var adapter: ComplianceQueueRepositoryAdapter

    @Autowired
    lateinit var jpaRepository: ComplianceQueueJpaRepository

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()

    private fun onChainEntry(amount: String = "1500") =
        OnChainEntry(
            amount = MonetaryAmount.of(amount),
            token = StablecoinToken.USDC,
            chainId = ChainId.EVM,
            txHash = "0xabc123def456",
            blockNumber = 42L,
            walletAddress = "0xwallet",
            tokenContract = "0xcontract",
        )

    private fun missingDataItem(
        tenantId: TenantId,
        txHash: String = "0xabc123def456",
    ): ComplianceQueueItem {
        val entry = onChainEntry()
        val result =
            TravelRuleValidationResult.MissingData(
                entry = entry.copy(txHash = txHash),
                reason = "Travel rule data required for transfers >= 1000",
            )
        return ComplianceQueueItem.from(result, tenantId)
    }

    private fun incompleteDataItem(tenantId: TenantId): ComplianceQueueItem {
        val entry = onChainEntry()
        val result =
            TravelRuleValidationResult.IncompleteData(
                entry = entry,
                missingFields = listOf("originator.vaspDid", "beneficiary.vaspDid"),
            )
        return ComplianceQueueItem.from(result, tenantId)
    }

    @Test
    fun `enqueue MISSING_DATA item persists all fields correctly`() {
        val item = missingDataItem(tenantA)
        adapter.enqueue(item)
        entityManager.flush()
        entityManager.clear()

        val found = jpaRepository.findById(item.id).orElse(null)
        assertNotNull(found)
        assertEquals("0xabc123def456", found.txHash)
        assertEquals("EVM", found.chainId)
        assertEquals("MISSING_DATA", found.reason)
        assertEquals("PENDING", found.status)
        assertEquals("[]", found.missingFields)
    }

    @Test
    fun `enqueue INCOMPLETE_DATA item persists missingFields as JSONB list`() {
        val item = incompleteDataItem(tenantA)
        adapter.enqueue(item)
        entityManager.flush()
        entityManager.clear()

        val found = jpaRepository.findById(item.id).orElse(null)
        assertNotNull(found)
        assertEquals("INCOMPLETE_DATA", found.reason)

        val mapper = jacksonObjectMapper()
        val fields: List<String> = mapper.readValue(found.missingFields)
        assertEquals(listOf("originator.vaspDid", "beneficiary.vaspDid"), fields)
    }

    @Test
    fun `enqueue stores the correct tenant_id for data isolation`() {
        val item = missingDataItem(tenantA, txHash = "0xtenant-check")
        adapter.enqueue(item)
        entityManager.flush()
        entityManager.clear()

        val found = jpaRepository.findById(item.id).orElse(null)
        assertNotNull(found)
        assertEquals(tenantA.value, found.tenantId, "Row must be stored with tenantA's UUID")
    }
}
