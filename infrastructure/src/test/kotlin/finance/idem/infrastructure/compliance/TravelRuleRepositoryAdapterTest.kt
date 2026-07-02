package finance.idem.infrastructure.compliance

import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.compliance.LegalPerson
import finance.idem.core.compliance.NaturalPerson
import finance.idem.core.compliance.TravelRuleData
import finance.idem.core.compliance.VaspTransferParty
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(TravelRuleRepositoryAdapter::class, PersistenceTestConfig::class)
class TravelRuleRepositoryAdapterTest {
    companion object {
        @Container
        val postgres =
            PostgreSQLContainer("postgres:16")
                .withDatabaseName("idem_test")
                .withUsername("idem")
                .withPassword("idem")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var adapter: TravelRuleRepositoryAdapter

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()

    private val naturalParty =
        VaspTransferParty(
            naturalPerson =
                NaturalPerson(
                    firstName = "Jane",
                    lastName = "Doe",
                    dateOfBirth = LocalDate.of(1990, 6, 15),
                    nationalId = "CPF-123",
                    country = "BR",
                ),
            accountNumber = "0xAbCd1234",
            vaspDid = "did:example:originator",
        )

    private val legalParty =
        VaspTransferParty(
            legalPerson =
                LegalPerson(
                    name = "Acme Corp",
                    registrationNumber = "12.345.678/0001-90",
                    country = "US",
                ),
            accountNumber = "GB29NWBK60161331926819",
            vaspDid = "did:example:beneficiary",
        )

    private fun travelRuleData(transferId: String = "tx-001") =
        TravelRuleData(
            transferId = transferId,
            originator = naturalParty,
            beneficiary = legalParty,
            transferAmount = MonetaryAmount.of("1500"),
            transferAsset = StablecoinToken.USDC,
        )

    @Test
    fun `save and findByTransferId round-trip preserves all fields including JSONB subtrees`() {
        val data = travelRuleData()
        adapter.save(data, tenantA)
        entityManager.flush()
        entityManager.clear()

        val found = adapter.findByTransferId("tx-001", tenantA)

        assertNotNull(found)
        assertEquals("tx-001", found.transferId)
        assertEquals(MonetaryAmount.of("1500"), found.transferAmount)
        assertEquals(StablecoinToken.USDC, found.transferAsset)
        assertEquals(TravelRuleData.defaultThresholdFor(StablecoinToken.USDC), found.threshold)

        // originator — naturalPerson subtree
        assertNotNull(found.originator.naturalPerson)
        assertEquals("Jane", found.originator.naturalPerson!!.firstName)
        assertEquals("Doe", found.originator.naturalPerson!!.lastName)
        assertEquals(LocalDate.of(1990, 6, 15), found.originator.naturalPerson!!.dateOfBirth)
        assertEquals("CPF-123", found.originator.naturalPerson!!.nationalId)
        assertEquals("BR", found.originator.naturalPerson!!.country)
        assertEquals("0xAbCd1234", found.originator.accountNumber)

        // beneficiary — legalPerson subtree
        assertNotNull(found.beneficiary.legalPerson)
        assertEquals("Acme Corp", found.beneficiary.legalPerson!!.name)
        assertEquals("12.345.678/0001-90", found.beneficiary.legalPerson!!.registrationNumber)
        assertEquals("US", found.beneficiary.legalPerson!!.country)
    }

    @Test
    fun `findByTransferId returns null when transferId does not exist`() {
        assertNull(adapter.findByTransferId("nonexistent", tenantA))
    }

    @Test
    fun `duplicate transferId for same tenant is idempotent — returns existing record`() {
        val first = adapter.save(travelRuleData("tx-dup"), tenantA)
        entityManager.flush()
        entityManager.clear()

        val second = adapter.save(travelRuleData("tx-dup"), tenantA)

        assertEquals(first.transferId, second.transferId)
        assertEquals(first.transferAmount, second.transferAmount)
    }

    @Test
    fun `RLS isolation — tenant A record is not visible to tenant B`() {
        adapter.save(travelRuleData("tx-rls"), tenantA)
        entityManager.flush()
        entityManager.clear()

        val found = adapter.findByTransferId("tx-rls", tenantB)

        assertNull(found, "Tenant B must not see tenant A's travel rule data")
    }
}
