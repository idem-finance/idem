package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.TenantId
import java.time.Instant
import java.util.UUID

/**
 * Read-side port for paginated access to [JournalLine] rows belonging to an account,
 * ordered reverse-chronologically (`createdAt DESC, id DESC`).
 *
 * Pagination is keyset-based ([afterCreatedAt]/[afterId] anchor a page to the last row of the
 * previous page) rather than offset-based (`Pageable`/`Page<T>`). On an append-only, high-write
 * table like `journal_lines`, offset pagination drifts under concurrent inserts — rows shift
 * between pages, causing skipped or duplicated entries. Keyset pagination is stable because
 * each page is anchored to a specific row rather than a row count.
 */
interface JournalLineRepository {
    /**
     * Returns up to [limit] journal lines for [accountId] within [tenantId], ordered by
     * `createdAt DESC, id DESC`.
     *
     * @param from inclusive lower bound on `createdAt`, or `null` for no lower bound
     * @param to inclusive upper bound on `createdAt`, or `null` for no upper bound
     * @param afterCreatedAt `createdAt` of the last row of the previous page (keyset anchor),
     *   or `null` for the first page
     * @param afterId `id` of the last row of the previous page (keyset tiebreaker for equal
     *   `createdAt`), or `null` for the first page
     * @param limit maximum number of rows to return
     */
    fun findByAccountId(
        accountId: AccountId,
        tenantId: TenantId,
        from: Instant?,
        to: Instant?,
        afterCreatedAt: Instant?,
        afterId: UUID?,
        limit: Int,
    ): List<JournalLine>

    fun countByAccountId(accountId: AccountId, tenantId: TenantId): Long
}
