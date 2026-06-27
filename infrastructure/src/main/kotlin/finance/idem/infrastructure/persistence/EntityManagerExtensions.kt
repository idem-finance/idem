package finance.idem.infrastructure.persistence

import finance.idem.core.TenantId
import jakarta.persistence.EntityManager

// UUID contains only hex digits and dashes — safe to interpolate without binding.
fun EntityManager.setRlsTenantId(tenantId: TenantId) {
    createNativeQuery("SET LOCAL app.tenant_id = '${tenantId.value}'")
        .executeUpdate()
}
