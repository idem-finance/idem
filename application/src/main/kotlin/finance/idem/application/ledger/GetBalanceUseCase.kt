package finance.idem.application.ledger

interface GetBalanceUseCase {
    fun execute(query: GetBalanceQuery): Result<Balance>
}
