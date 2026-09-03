package finance.idem.application.tenant

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TenantProvisioningModelsTest {
    private val tenantId = TenantId.generate()

    @Test
    fun `ProvisionTenantCommand holds all fields`() {
        val cmd = ProvisionTenantCommand("admin-token", "idem-key-1", "Acme", "ops@acme.com")

        assertEquals("admin-token", cmd.adminToken)
        assertEquals("idem-key-1", cmd.idempotencyKey)
        assertEquals("Acme", cmd.organizationName)
        assertEquals("ops@acme.com", cmd.contactEmail)
        assertEquals(cmd, cmd.copy())
    }

    @Test
    fun `ProvisionedTenant holds all fields`() {
        val result = ProvisionedTenant(tenantId, "sk_live_abc", "https://cloud.idem.finance/t/${tenantId.value}")

        assertEquals(tenantId, result.tenantId)
        assertEquals("sk_live_abc", result.rawApiKey)
        assertEquals("https://cloud.idem.finance/t/${tenantId.value}", result.dashboardUrl)
        assertEquals(result, result.copy())
    }

    @Test
    fun `InvalidAdminToken carries message and is an Exception`() {
        val error = InvalidAdminToken("missing token")

        assertIs<Exception>(error)
        assertEquals("missing token", error.message)
    }

    @Test
    fun `TenantNotFound carries message and is an Exception`() {
        val error = TenantNotFound("no tenant for id")

        assertIs<Exception>(error)
        assertEquals("no tenant for id", error.message)
    }

    @Test
    fun `ProvisioningInProgress carries message and is an Exception`() {
        val error = ProvisioningInProgress("already in progress")

        assertIs<Exception>(error)
        assertEquals("already in progress", error.message)
    }
}
