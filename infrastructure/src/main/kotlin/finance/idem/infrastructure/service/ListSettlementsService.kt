package finance.idem.infrastructure.service

import finance.idem.application.ledger.InvalidCursor
import finance.idem.application.settlement.ListSettlementsQuery
import finance.idem.application.settlement.ListSettlementsUseCase
import finance.idem.application.settlement.SettlementCursor
import finance.idem.application.settlement.SettlementPage
import finance.idem.core.ledger.SettlementRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ListSettlementsService(
    private val settlementRepository: SettlementRepository,
) : ListSettlementsUseCase {

    @Transactional(readOnly = true)
    override fun execute(query: ListSettlementsQuery): Result<SettlementPage> {
        val rawCursor = query.cursor
        val cursor = if (rawCursor != null) {
            SettlementCursor.decode(rawCursor).getOrElse {
                return Result.failure(InvalidCursor(rawCursor))
            }
        } else null

        val rows = settlementRepository.findPage(
            tenantId = query.tenantId,
            status = query.status,
            from = query.from,
            to = query.to,
            afterCreatedAt = cursor?.createdAt,
            afterId = cursor?.id,
            limit = query.limit,
        )

        val nextCursor = if (rows.size == query.limit) {
            val last = rows.last()
            SettlementCursor(last.createdAt, last.id).encode()
        } else null

        return Result.success(SettlementPage(rows, nextCursor))
    }
}
