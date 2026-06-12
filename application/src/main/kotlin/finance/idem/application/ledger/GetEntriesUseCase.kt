package finance.idem.application.ledger

interface GetEntriesUseCase {
    fun execute(query: GetEntriesQuery): Result<EntryPage>
}
