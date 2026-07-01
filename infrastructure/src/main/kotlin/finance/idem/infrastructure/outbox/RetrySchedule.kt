package finance.idem.infrastructure.outbox

import java.time.Duration

/**
 * Backoff schedule for webhook delivery retries (#55).
 *
 * `attempts` is the 1-based count *after* the current failure. Attempts 1-4
 * retry with increasing backoff; attempt `maxAttempts` (default 5) has no
 * further retry — the caller should mark the row DEAD instead.
 */
object RetrySchedule {
    private val backoff =
        mapOf(
            1 to Duration.ofSeconds(5),
            2 to Duration.ofSeconds(30),
            3 to Duration.ofMinutes(2),
            4 to Duration.ofMinutes(10),
        )

    /**
     * Highest valid value for `idem.webhook.max-attempts`. `backoff` defines delays
     * for attempts `1..(MAX_SUPPORTED_ATTEMPTS - 1)`; attempt `MAX_SUPPORTED_ATTEMPTS`
     * has no delay and is the terminal attempt that triggers `DEAD`. Values above
     * this are rejected by [WebhookOutboxPoller]'s init check -- [nextRetryDelay]
     * would otherwise return `null` (no entry in `backoff`) for attempts in
     * `MAX_SUPPORTED_ATTEMPTS until maxAttempts`, causing premature `DEAD` marking.
     */
    val MAX_SUPPORTED_ATTEMPTS = backoff.size + 1

    fun nextRetryDelay(
        attempts: Int,
        maxAttempts: Int,
    ): Duration? = if (attempts >= maxAttempts) null else backoff[attempts]
}
