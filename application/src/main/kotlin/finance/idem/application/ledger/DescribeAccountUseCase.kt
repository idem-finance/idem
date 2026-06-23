package finance.idem.application.ledger

interface DescribeAccountUseCase {
    fun execute(query: DescribeAccountQuery): Result<AccountDescription>
}
