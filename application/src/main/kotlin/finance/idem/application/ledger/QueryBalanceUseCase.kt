package finance.idem.application.ledger

interface QueryBalanceUseCase {
    fun execute(query: QueryBalanceQuery): Result<Balance>
}
