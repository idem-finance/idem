package finance.idem.application.port

/** IP-keyed brute-force throttle on the internal admin token (#272 review finding). */
interface AdminTokenLockoutGuard {
    fun isLockedOut(clientIp: String): Boolean

    fun recordFailure(clientIp: String)
}
