package finance.idem.infrastructure.service

import finance.idem.application.ledger.EntriesAccountNotFound
import finance.idem.application.ledger.EntryCursor
import finance.idem.application.ledger.EntryPage
import finance.idem.application.ledger.InvalidCursor
import finance.idem.application.ledger.GetEntriesQuery
import finance.idem.application.ledger.QueryEntriesUseCase
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.JournalLineRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class QueryEntriesService(
    private val accountRepository: AccountRepository,
    private val journalLineRepository: JournalLineRepository,
) : QueryEntriesUseCase {

    override fun execute(query: GetEntriesQuery): Result<EntryPage> {
        accountRepository.findById(query.accountId, query.tenantId)
            ?: return Result.failure(EntriesAccountNotFound(query.accountId))

        val anchor = query.cursor?.let { token ->
            EntryCursor.decode(token).getOrElse { return Result.failure(InvalidCursor(token)) }
        }

        val rows = journalLineRepository.findByAccountId(
            accountId = query.accountId,
            tenantId = query.tenantId,
            from = query.from,
            to = query.to,
            afterCreatedAt = anchor?.createdAt,
            afterId = anchor?.id,
            limit = query.limit + 1,
        )

        val hasMore = rows.size > query.limit
        val entries = if (hasMore) rows.take(query.limit) else rows
        val nextCursor = if (hasMore) {
            entries.last().let { EntryCursor(it.createdAt, it.id).encode() }
        } else {
            null
        }

        return Result.success(EntryPage(query.accountId, entries, nextCursor))
    }
}
