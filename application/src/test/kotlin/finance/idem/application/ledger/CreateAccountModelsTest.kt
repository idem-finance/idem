package finance.idem.application.ledger

import finance.idem.core.FiatCurrency
import finance.idem.core.TenantId
import finance.idem.core.ledger.AccountType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreateAccountModelsTest {
    private val tenantId = TenantId.generate()

    @Test
    fun `CreateAccountCommand holds all fields`() {
        val cmd =
            CreateAccountCommand(
                tenantId = tenantId,
                name = "USDC Wallet",
                description = "Incoming settlements",
                currency = FiatCurrency.USD,
                type = AccountType.ASSET,
                createdBy = "sk_live_abc",
            )

        assertEquals(tenantId, cmd.tenantId)
        assertEquals("USDC Wallet", cmd.name)
        assertEquals("Incoming settlements", cmd.description)
        assertEquals(FiatCurrency.USD, cmd.currency)
        assertEquals(AccountType.ASSET, cmd.type)
        assertEquals("sk_live_abc", cmd.createdBy)
    }

    @Test
    fun `CreateAccountCommand description is nullable`() {
        val cmd =
            CreateAccountCommand(
                tenantId = tenantId,
                name = "Fees",
                description = null,
                currency = FiatCurrency.BRL,
                type = AccountType.REVENUE,
                createdBy = "sk_live_xyz",
            )

        assertNull(cmd.description)
    }

    @Test
    fun `ListAccountsQuery holds tenantId`() {
        val query = ListAccountsQuery(tenantId)

        assertEquals(tenantId, query.tenantId)
    }

    @Test
    fun `ListAccountsQuery equality is value-based`() {
        val a = ListAccountsQuery(tenantId)
        val b = ListAccountsQuery(tenantId)

        assertEquals(a, b)
    }
}
