package finance.idem.application.port

import finance.idem.application.tenant.ProvisionedTenant

interface TenantProvisioningIdempotencyStore {
    /**
     * Atomically claims [key]. Returns `true` if newly claimed (caller must proceed to
     * provision and then call [cache]), `false` if a claim already exists (caller must
     * check [findCached]).
     */
    fun claim(key: String): Boolean

    /** The cached result of a prior claim under [key], or `null` if that claim hasn't resolved yet. */
    fun findCached(key: String): ProvisionedTenant?

    /** Records the final result for a successful claim under [key], available for later replay. */
    fun cache(
        key: String,
        result: ProvisionedTenant,
    )

    /** Releases a claim that failed mid-flight, so a retry isn't blocked until the claim TTL expires. */
    fun release(key: String)
}
