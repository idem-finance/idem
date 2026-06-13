package finance.idem.application.ledger

interface GenerateStatementUseCase {
    fun execute(query: GenerateStatementQuery): Result<AccountStatement>
}
