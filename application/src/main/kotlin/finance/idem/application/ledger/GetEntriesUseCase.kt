package finance.idem.application.ledger

interface QueryEntriesUseCase {
    fun execute(query: GetEntriesQuery): Result<EntryPage>
}
