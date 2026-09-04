package finance.idem.infrastructure.persistence

import finance.idem.core.TenantId
import jakarta.persistence.EntityManager

/**
 * Scopes the rest of the current transaction to idem_app and the given tenant. Postgres
 * refuses to ever strip SUPERUSER from the role this app authenticates as (the bootstrap
 * role initdb creates) -- V31 works around that by creating a separate, NOLOGIN idem_app
 * role instead and granting the connecting role membership in it. `SET LOCAL ROLE`, like
 * `SET LOCAL app.tenant_id` below it, is transaction-scoped and reverts automatically at
 * commit/rollback -- once `current_user` is idem_app for this transaction, RLS's
 * superuser/owner-exemption checks evaluate against idem_app's attributes (non-super,
 * non-owner of anything), not the connecting role's, which is what actually makes RLS
 * enforce for this call's queries.
 *
 * Every call site that skips this (a handful of deliberate, reviewed cross-tenant reads --
 * see V31's `service_cross_tenant_read` policies -- or a future call site that simply forgot
 * it) keeps running as the connecting role, which still bypasses RLS entirely via SUPERUSER.
 * That's a real, accepted tradeoff of not requiring a second authenticated connection/role
 * per idem#286's investigation: it's an application-level convention (this one call site,
 * consistently used by every tenant-scoped repository adapter), not a connection-level
 * default. It does not weaken this module's own audited call sites.
 */
fun EntityManager.setRlsTenantId(tenantId: TenantId) {
    createNativeQuery("SET LOCAL ROLE idem_app").executeUpdate()
    // UUID contains only hex digits and dashes — safe to interpolate without binding.
    createNativeQuery("SET LOCAL app.tenant_id = '${tenantId.value}'").executeUpdate()
}
