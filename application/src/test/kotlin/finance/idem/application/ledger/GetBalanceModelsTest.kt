package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GetBalanceModelsTest {
    private val accountId = AccountId.generate()
    private val tenantId = TenantId.generate()
    private val now = Instant.now()

    @Test
    fun `GetBalanceQuery holds all fields`() {
        val query = GetBalanceQuery(accountId, tenantId, asOf = now)
        assertEquals(accountId, query.accountId)
        assertEquals(tenantId, query.tenantId)
        assertEquals(now, query.asOf)
        assertEquals(query, query.copy())
    }

    @Test
    fun `GetBalanceQuery asOf defaults to null`() {
        val query = GetBalanceQuery(accountId, tenantId)
        assertEquals(null, query.asOf)
    }

    @Test
    fun `Balance holds all fields`() {
        val balance = Balance(accountId, FiatCurrency.BRL, MonetaryAmount.of("500"), EntryType.DEBIT, now)
        assertEquals(accountId, balance.accountId)
        assertEquals(FiatCurrency.BRL, balance.currency)
        assertEquals(MonetaryAmount.of("500"), balance.amount)
        assertEquals(EntryType.DEBIT, balance.normalBalance)
        assertEquals(now, balance.computedAt)
        assertEquals(balance, balance.copy())
    }

    @Test
    fun `GetBalanceError AccountNotFound carries accountId and message`() {
        val error = BalanceAccountNotFound(accountId)
        assertEquals(accountId, error.accountId)
        assertIs<GetBalanceError>(error)
    }
}
