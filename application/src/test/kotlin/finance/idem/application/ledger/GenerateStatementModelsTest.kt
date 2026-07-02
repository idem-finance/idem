package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GenerateStatementModelsTest {
    private val accountId = AccountId.generate()
    private val tenantId = TenantId.generate()
    private val now = Instant.now()

    @Test
    fun `GenerateStatementQuery holds all fields`() {
        val query = GenerateStatementQuery(accountId, tenantId, from = now, to = now)
        assertEquals(accountId, query.accountId)
        assertEquals(tenantId, query.tenantId)
        assertEquals(now, query.from)
        assertEquals(now, query.to)
        assertEquals(query, query.copy())
    }

    @Test
    fun `StatementMovement holds all fields`() {
        val txId = TransactionId.generate()
        val movement =
            StatementMovement(
                transactionId = txId,
                type = EntryType.DEBIT,
                amount = MonetaryAmount.of("500"),
                description = "Pix received",
                occurredAt = now,
            )
        assertEquals(txId, movement.transactionId)
        assertEquals(EntryType.DEBIT, movement.type)
        assertEquals(MonetaryAmount.of("500"), movement.amount)
        assertEquals("Pix received", movement.description)
        assertEquals(now, movement.occurredAt)
        assertEquals(movement, movement.copy())
    }

    @Test
    fun `StatementMovement description may be null`() {
        val movement =
            StatementMovement(
                transactionId = TransactionId.generate(),
                type = EntryType.CREDIT,
                amount = MonetaryAmount.of("100"),
                description = null,
                occurredAt = now,
            )
        assertNull(movement.description)
    }

    @Test
    fun `AccountStatement holds all fields`() {
        val movement =
            StatementMovement(
                transactionId = TransactionId.generate(),
                type = EntryType.DEBIT,
                amount = MonetaryAmount.of("500"),
                description = null,
                occurredAt = now,
            )
        val statement =
            AccountStatement(
                accountId = accountId,
                currency = FiatCurrency.BRL,
                from = now,
                to = now,
                openingBalance = MonetaryAmount.of("1000"),
                closingBalance = MonetaryAmount.of("1500"),
                movements = listOf(movement),
            )
        assertEquals(accountId, statement.accountId)
        assertEquals(FiatCurrency.BRL, statement.currency)
        assertEquals(now, statement.from)
        assertEquals(now, statement.to)
        assertEquals(MonetaryAmount.of("1000"), statement.openingBalance)
        assertEquals(MonetaryAmount.of("1500"), statement.closingBalance)
        assertEquals(listOf(movement), statement.movements)
        assertEquals(statement, statement.copy())
    }

    @Test
    fun `StatementAccountNotFound carries accountId and message`() {
        val error = StatementAccountNotFound(accountId)

        assertEquals(accountId, error.accountId)
        assertIs<GenerateStatementError>(error)
    }

    @Test
    fun `InvalidStatementRange carries from and to and message`() {
        val from = now.plusSeconds(3600)
        val to = now

        val error = InvalidStatementRange(from, to)

        assertEquals(from, error.from)
        assertEquals(to, error.to)
        assertIs<GenerateStatementError>(error)
    }
}
