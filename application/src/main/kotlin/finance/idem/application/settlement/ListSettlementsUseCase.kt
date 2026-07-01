package finance.idem.application.settlement

interface ListSettlementsUseCase {
    fun execute(query: ListSettlementsQuery): Result<SettlementPage>
}
