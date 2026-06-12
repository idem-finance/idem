package finance.idem.application.ledger

interface QueryEntriesUseCase {
    fun execute(query: QueryEntriesQuery): Result<EntryPage>
}
