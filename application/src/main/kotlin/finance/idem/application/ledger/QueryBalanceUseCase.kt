package finance.idem.application.ledger

interface QueryBalanceUseCase {
    fun execute(query: GetBalanceQuery): Result<Balance>
}
