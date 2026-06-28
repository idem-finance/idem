package finance.idem.infrastructure.compliance

import finance.idem.core.TenantId
import finance.idem.infrastructure.persistence.PersistenceTestConfig
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
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(LgpdRetentionRepositoryAdapter::class, LgpdRetentionService::class, PersistenceTestConfig::class)
class LgpdRetentionServiceTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16")
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

    @Autowired lateinit var adapter: LgpdRetentionRepositoryAdapter
    @Autowired lateinit var service: LgpdRetentionService
    @Autowired lateinit var scheduleRepo: LgpdRetentionScheduleJpaRepository
    @Autowired lateinit var travelRuleDataRepo: TravelRuleDataJpaRepository

    private val tenantA = TenantId.generate()

    private fun saveTravelRuleData(transferId: String, tenantId: TenantId): TravelRuleDataDataModel {
        val model = TravelRuleDataDataModel(
            id = UUID.randomUUID(),
            tenantId = tenantId.value,
            transferId = transferId,
            originator = """{"accountNumber":"0xabc","vaspDid":"did:vasp:orig"}""",
            beneficiary = """{"accountNumber":"0xdef","vaspDid":"did:vasp:benef"}""",
            transferAmount = BigDecimal("1500.00"),
            transferAsset = "USDC",
            threshold = BigDecimal("1000.00"),
            createdAt = Instant.now(),
        )
        return travelRuleDataRepo.save(model)
    }

    @Test
    fun `schedule persists a row with correct deletion_due_at`() {
        adapter.schedule(tenantA, "TravelRuleData", "transfer-abc", 7)

        val rows = scheduleRepo.findAll()
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals(tenantA.value, row.tenantId)
        assertEquals("TravelRuleData", row.entityType)
        assertEquals("transfer-abc", row.entityId)
        assertEquals(7, row.retentionYears)
        assertNull(row.processedAt)
        // deletion_due_at should be approximately 7 years from now
        val sevenYearsFromNow = Instant.now().plus(7 * 365L, ChronoUnit.DAYS)
        assert(row.deletionDueAt.isAfter(Instant.now())) { "deletionDueAt must be in the future" }
        assert(row.deletionDueAt.isBefore(sevenYearsFromNow.plusSeconds(60))) { "deletionDueAt must be ~7 years out" }
    }

    @Test
    fun `processExpiredData deletes referenced TravelRuleData and marks entry as processed`() {
        val transferId = "transfer-expired-001"
        saveTravelRuleData(transferId, tenantA)

        // Insert a past-due schedule entry directly (bypass adapter to control deletionDueAt)
        scheduleRepo.save(
            LgpdRetentionScheduleDataModel(
                id = UUID.randomUUID(),
                tenantId = tenantA.value,
                entityType = "TravelRuleData",
                entityId = transferId,
                retentionYears = 7,
                scheduledAt = Instant.now().minus(8 * 365L, ChronoUnit.DAYS),
                deletionDueAt = Instant.now().minus(1, ChronoUnit.DAYS),
            )
        )

        service.processExpiredData()

        val scheduleRows = scheduleRepo.findAll()
        assertEquals(1, scheduleRows.size)
        assertNotNull(scheduleRows.first().processedAt, "expired entry must be marked processed")

        val travelRuleRows = travelRuleDataRepo.findByTransferIdAndTenantId(transferId, tenantA.value)
        assertNull(travelRuleRows, "TravelRuleData must be deleted after retention expiry")
    }

    @Test
    fun `processExpiredData leaves future-due entries untouched`() {
        val transferId = "transfer-future-001"
        saveTravelRuleData(transferId, tenantA)

        scheduleRepo.save(
            LgpdRetentionScheduleDataModel(
                id = UUID.randomUUID(),
                tenantId = tenantA.value,
                entityType = "TravelRuleData",
                entityId = transferId,
                retentionYears = 7,
                scheduledAt = Instant.now(),
                deletionDueAt = Instant.now().plus(7 * 365L, ChronoUnit.DAYS),
            )
        )

        service.processExpiredData()

        val scheduleRows = scheduleRepo.findAll()
        assertEquals(1, scheduleRows.size)
        assertNull(scheduleRows.first().processedAt, "future-due entry must not be processed")

        val travelRuleRow = travelRuleDataRepo.findByTransferIdAndTenantId(transferId, tenantA.value)
        assertNotNull(travelRuleRow, "TravelRuleData must NOT be deleted for future-due entries")
    }

    @Test
    fun `processExpiredData skips already-processed entries`() {
        val transferId = "transfer-already-processed"
        val alreadyProcessedAt = Instant.now().minus(1, ChronoUnit.HOURS)

        scheduleRepo.save(
            LgpdRetentionScheduleDataModel(
                id = UUID.randomUUID(),
                tenantId = tenantA.value,
                entityType = "TravelRuleData",
                entityId = transferId,
                retentionYears = 7,
                scheduledAt = Instant.now().minus(8 * 365L, ChronoUnit.DAYS),
                deletionDueAt = Instant.now().minus(1, ChronoUnit.DAYS),
                processedAt = alreadyProcessedAt,
            )
        )

        service.processExpiredData()

        val row = scheduleRepo.findAll().first()
        assertEquals(alreadyProcessedAt, row.processedAt, "processedAt must not be changed for already-processed entries")
    }
}
