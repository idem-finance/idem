package finance.idem.core.tenant

import finance.idem.core.TenantId

interface TenantConfigRepository {
    /**
     * Returns this tenant's plan/limits/flags config, or `null` if no row exists yet.
     * Callers must treat `null` as "not configured" (use [TenantConfig.default]), not as
     * an error.
     */
    fun findByTenantId(tenantId: TenantId): TenantConfig?

    /**
     * Persists (insert or update) a tenant's plan/limits/flags config.
     */
    fun upsert(config: TenantConfig)
}
