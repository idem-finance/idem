package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DescribeAccountModelsTest {

    private val accountId = AccountId.generate()
    private val tenantId = TenantId.generate()
    private val now = Instant.now()

    @Test
    fun `DescribeAccountQuery holds all fields`() {
        val query = DescribeAccountQuery(accountId, tenantId)
        assertEquals(accountId, query.accountId)
        assertEquals(tenantId, query.tenantId)
        assertEquals(query, query.copy())
    }

    @Test
    fun `DescribeAccountAccountNotFound carries accountId in message`() {
        val error = DescribeAccountAccountNotFound(accountId)
        assertEquals("Account not found: ${accountId.value}", error.message)
    }

    @Test
    fun `AccountDescription holds all fields`() {
        val balance = Balance(accountId, FiatCurrency.USD, MonetaryAmount.of("100"), EntryType.DEBIT, now)
        val desc = AccountDescription(
            accountId = accountId,
            name = "Ops",
            description = "Operating account",
            currency = FiatCurrency.USD,
            entryCount = 5L,
            lastActivityAt = now,
            balance = balance,
        )
        assertEquals(accountId, desc.accountId)
        assertEquals("Ops", desc.name)
        assertEquals("Operating account", desc.description)
        assertEquals(FiatCurrency.USD, desc.currency)
        assertEquals(5L, desc.entryCount)
        assertEquals(now, desc.lastActivityAt)
        assertEquals(balance, desc.balance)
        assertEquals(desc, desc.copy())
    }

    @Test
    fun `AccountDescription description and lastActivityAt can be null`() {
        val balance = Balance(accountId, FiatCurrency.BRL, MonetaryAmount.ZERO, EntryType.DEBIT, now)
        val desc = AccountDescription(
            accountId = accountId,
            name = "Empty",
            description = null,
            currency = FiatCurrency.BRL,
            entryCount = 0L,
            lastActivityAt = null,
            balance = balance,
        )
        assertNull(desc.description)
        assertNull(desc.lastActivityAt)
    }
}
