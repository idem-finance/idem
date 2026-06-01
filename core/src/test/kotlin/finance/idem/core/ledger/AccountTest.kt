package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AccountTest {

    private val now = Instant.now()
    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()

    @Test
    fun `create derives normalBalance from AccountType`() {
        assertEquals(EntryType.DEBIT, AccountType.ASSET.normalBalance())
        assertEquals(EntryType.DEBIT, AccountType.EXPENSE.normalBalance())
        assertEquals(EntryType.CREDIT, AccountType.LIABILITY.normalBalance())
        assertEquals(EntryType.CREDIT, AccountType.EQUITY.normalBalance())
        assertEquals(EntryType.CREDIT, AccountType.REVENUE.normalBalance())
    }

    @Test
    fun `create builds Account with derived normalBalance`() {
        val account = Account.create(
            id = accountId,
            tenantId = tenantId,
            name = "Nostro USDC Base",
            currency = FiatCurrency.USD,
            type = AccountType.ASSET,
            createdAt = now,
            createdBy = "sk_live_xxxx",
        )
        assertEquals(EntryType.DEBIT, account.normalBalance)
        assertEquals(AccountType.ASSET, account.type)
        assertEquals("Nostro USDC Base", account.name)
        assertEquals(FiatCurrency.USD, account.currency)
        assertEquals("sk_live_xxxx", account.createdBy)
        assertEquals(now, account.createdAt)
    }

    @Test
    fun `create with optional description`() {
        val account = Account.create(
            id = accountId,
            tenantId = tenantId,
            name = "Customer BRL Payable",
            currency = FiatCurrency.BRL,
            type = AccountType.LIABILITY,
            createdAt = now,
            createdBy = "system",
            description = "Fiat obligation to customer after offramp",
        )
        assertEquals("Fiat obligation to customer after offramp", account.description)
        assertEquals(EntryType.CREDIT, account.normalBalance)
    }

    @Test
    fun `description defaults to null`() {
        val account = Account.create(
            id = accountId,
            tenantId = tenantId,
            name = "Revenue Fees",
            currency = FiatCurrency.BRL,
            type = AccountType.REVENUE,
            createdAt = now,
            createdBy = "system",
        )
        assertNull(account.description)
    }

    @Test
    fun `updatedAt and updatedBy default to null on creation`() {
        val account = Account.create(
            id = accountId,
            tenantId = tenantId,
            name = "Gas Expense",
            currency = FiatCurrency.USD,
            type = AccountType.EXPENSE,
            createdAt = now,
            createdBy = "system",
        )
        assertNull(account.updatedAt)
        assertNull(account.updatedBy)
    }

    @Test
    fun `copy with updatedAt and updatedBy reflects a mutation`() {
        val original = Account.create(
            id = accountId,
            tenantId = tenantId,
            name = "Original Name",
            currency = FiatCurrency.USD,
            type = AccountType.ASSET,
            createdAt = now,
            createdBy = "system",
        )
        val updatedAt = now.plusSeconds(3600)
        val updated = original.copy(
            name = "Updated Name",
            updatedAt = updatedAt,
            updatedBy = "sk_live_yyyy",
        )
        assertEquals("Updated Name", updated.name)
        assertEquals(updatedAt, updated.updatedAt)
        assertEquals("sk_live_yyyy", updated.updatedBy)
        // createdAt/createdBy must not change on update
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(original.createdBy, updated.createdBy)
    }

    @Test
    fun `normalBalance re-derives when type changes via copy`() {
        val asset = Account.create(
            id = accountId,
            tenantId = tenantId,
            name = "Test Account",
            currency = FiatCurrency.USD,
            type = AccountType.ASSET,
            createdAt = now,
            createdBy = "system",
        )
        assertEquals(EntryType.DEBIT, asset.normalBalance)

        // copy with different type re-derives normalBalance automatically
        val liability = asset.copy(type = AccountType.LIABILITY)
        assertEquals(EntryType.CREDIT, liability.normalBalance)
    }

    @Test
    fun `reconstitute rebuilds account from persisted data with all fields`() {
        val updatedAt = now.plusSeconds(3600)
        val account = Account.reconstitute(
            id = accountId,
            tenantId = tenantId,
            name = "Reconstructed",
            currency = FiatCurrency.USD,
            type = AccountType.LIABILITY,
            createdAt = now,
            createdBy = "system",
            description = "From DB",
            updatedAt = updatedAt,
            updatedBy = "sk_live_yyyy",
        )
        assertEquals(accountId, account.id)
        assertEquals("Reconstructed", account.name)
        assertEquals("From DB", account.description)
        assertEquals(EntryType.CREDIT, account.normalBalance)
        assertEquals(updatedAt, account.updatedAt)
        assertEquals("sk_live_yyyy", account.updatedBy)
    }

    @Test
    fun `reconstitute with only required fields uses null defaults`() {
        val account = Account.reconstitute(
            id = accountId,
            tenantId = tenantId,
            name = "Minimal",
            currency = FiatCurrency.BRL,
            type = AccountType.ASSET,
            createdAt = now,
            createdBy = "system",
        )
        assertNull(account.description)
        assertNull(account.updatedAt)
        assertNull(account.updatedBy)
        assertEquals(EntryType.DEBIT, account.normalBalance)
    }

    @Test
    fun `all AccountType values produce correct normalBalance`() {
        val expectations = mapOf(
            AccountType.ASSET to EntryType.DEBIT,
            AccountType.LIABILITY to EntryType.CREDIT,
            AccountType.EQUITY to EntryType.CREDIT,
            AccountType.REVENUE to EntryType.CREDIT,
            AccountType.EXPENSE to EntryType.DEBIT,
        )
        expectations.forEach { (type, expected) ->
            assertEquals(expected, type.normalBalance(), "Wrong normalBalance for $type")
        }
    }
}
