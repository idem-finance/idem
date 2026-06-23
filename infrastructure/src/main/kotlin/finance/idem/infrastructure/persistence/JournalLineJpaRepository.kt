package finance.idem.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface JournalLineJpaRepository : JpaRepository<JournalLineDataModel, UUID> {

    // Nullable filter params are wrapped in CAST(... AS type) so Postgres can resolve their type
    // when they appear only in an `IS NULL` context — without it, pgjdbc fails with
    // "could not determine data type of parameter $N".
    @Query(
        value = """
            SELECT * FROM journal_lines j
            WHERE j.account_id = :accountId AND j.tenant_id = :tenantId
              AND (CAST(:from AS timestamptz) IS NULL OR j.created_at >= CAST(:from AS timestamptz))
              AND (CAST(:to AS timestamptz) IS NULL OR j.created_at <= CAST(:to AS timestamptz))
              AND (
                CAST(:afterCreatedAt AS timestamptz) IS NULL
                OR j.created_at < CAST(:afterCreatedAt AS timestamptz)
                OR (j.created_at = CAST(:afterCreatedAt AS timestamptz) AND j.id < CAST(:afterId AS uuid))
              )
            ORDER BY j.created_at DESC, j.id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findPage(
        @Param("accountId") accountId: UUID,
        @Param("tenantId") tenantId: UUID,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        @Param("afterCreatedAt") afterCreatedAt: Instant?,
        @Param("afterId") afterId: UUID?,
        @Param("limit") limit: Int,
    ): List<JournalLineDataModel>

    @Query(
        value = "SELECT COUNT(*) FROM journal_lines WHERE account_id = :accountId AND tenant_id = :tenantId",
        nativeQuery = true,
    )
    fun countByAccountAndTenant(
        @Param("accountId") accountId: UUID,
        @Param("tenantId") tenantId: UUID,
    ): Long
}
