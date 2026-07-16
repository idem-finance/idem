package finance.idem.infrastructure.compliance

import finance.idem.core.TenantId
import finance.idem.infrastructure.SharedPostgresTestBase
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LgpdRetentionRepositoryAdapter::class, LgpdRetentionService::class, PersistenceTestConfig::class)
class LgpdRetentionServiceTest : SharedPostgresTestBase() {
    @Autowired lateinit var adapter: LgpdRetentionRepositoryAdapter

    @Autowired lateinit var service: LgpdRetentionService

    @Autowired lateinit var scheduleRepo: LgpdRetentionScheduleJpaRepository

    @Autowired lateinit var travelRuleDataRepo: TravelRuleDataJpaRepository

    private val tenantA = TenantId.generate()

    private fun saveTravelRuleData(
        transferId: String,
        tenantId: TenantId,
    ): TravelRuleDataDataModel {
        val model =
            TravelRuleDataDataModel(
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
        // deletion_due_at should be approximately 7 calendar years from now
        val sevenYearsFromNow =
            Instant
                .now()
                .atOffset(ZoneOffset.UTC)
                .plusYears(7)
                .toInstant()
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
            ),
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
            ),
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
            ),
        )

        service.processExpiredData()

        val row = scheduleRepo.findAll().first()
        assertEquals(alreadyProcessedAt, row.processedAt, "processedAt must not be changed for already-processed entries")
    }

    @Test
    fun `processExpiredData skips unknown entityType without marking it as processed`() {
        scheduleRepo.save(
            LgpdRetentionScheduleDataModel(
                id = UUID.randomUUID(),
                tenantId = TenantId.generate().value,
                entityType = "UnknownFutureType",
                entityId = "entity-001",
                retentionYears = 7,
                scheduledAt = Instant.now().minus(8 * 365L, ChronoUnit.DAYS),
                deletionDueAt = Instant.now().minus(1, ChronoUnit.DAYS),
            ),
        )

        service.processExpiredData()

        val row = scheduleRepo.findAll().first()
        assertNull(row.processedAt, "Unknown entityType must not be marked as processed — deletion obligation must be preserved")
    }
}
