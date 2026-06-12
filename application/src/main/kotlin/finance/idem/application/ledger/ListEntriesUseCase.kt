package finance.idem.application.ledger

interface ListEntriesUseCase {
    fun execute(query: ListEntriesQuery): Result<EntryPage>
}
