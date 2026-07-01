package finance.idem.infrastructure.outbox

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Blocks SSRF targets before the poller fires an outbound HTTP request.
 *
 * Rejected addresses:
 *  - Non-https scheme when [requireHttps] is true (production default)
 *  - RFC-1918 private ranges (10/8, 172.16/12, 192.168/16)
 *  - Loopback (127.0.0.0/8, ::1)
 *  - Link-local / IMDS (169.254.0.0/16, fe80::/10)
 *  - IPv6 unique-local (fc00::/7)
 *  - Hostnames: localhost, *.localhost, *.internal, *.local
 */
@Component
class SsrfWebhookUrlValidator(
    @Value("\${idem.webhook.require-https:true}") private val requireHttps: Boolean,
) : WebhookUrlValidator {
    override fun validate(rawUrl: String): Result<Unit> {
        val uri =
            runCatching { URI.create(rawUrl) }.getOrElse {
                return Result.failure(IllegalArgumentException("malformed webhook URL"))
            }

        val scheme = uri.scheme?.lowercase()
        if (requireHttps && scheme != "https") {
            return Result.failure(IllegalArgumentException("webhook URL must use https scheme; got: $scheme"))
        }
        if (scheme != "https" && scheme != "http") {
            return Result.failure(IllegalArgumentException("webhook URL scheme must be http or https; got: $scheme"))
        }

        val host =
            uri.host
                ?: return Result.failure(IllegalArgumentException("webhook URL has no resolvable host"))

        if (isBlockedHostname(host)) {
            return Result.failure(IllegalArgumentException("webhook URL host is not permitted: $host"))
        }

        val address =
            runCatching { InetAddress.getByName(host) }.getOrElse {
                return Result.failure(IllegalArgumentException("webhook URL host does not resolve: $host"))
            }

        if (isPrivateOrReserved(address)) {
            return Result.failure(IllegalArgumentException("webhook URL resolves to a private or reserved address"))
        }

        return Result.success(Unit)
    }

    private fun isBlockedHostname(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" ||
            h.endsWith(".localhost") ||
            h.endsWith(".internal") ||
            h.endsWith(".local")
    }

    private fun isPrivateOrReserved(address: InetAddress): Boolean =
        address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isAnyLocalAddress ||
            isUniqueLocalIpv6(address)

    // fc00::/7 — unique-local IPv6 (not covered by isSiteLocalAddress in modern JDKs)
    private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
        if (address !is Inet6Address) return false
        return (address.address[0].toInt() and 0xFE) == 0xFC
    }
}
