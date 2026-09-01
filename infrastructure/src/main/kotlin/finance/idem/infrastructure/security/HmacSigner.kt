package finance.idem.infrastructure.security

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HmacSigner {
    fun hexHmacSha256(
        secret: String,
        body: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * Constant-time comparison of an HMAC-SHA256 signature against [signedContent] signed
     * with [secret]. Callers pass whatever the provider actually signs — e.g. the raw body,
     * or a provider-specific concatenation such as QuickNode's `nonce + timestamp + body`.
     */
    fun verify(
        secret: String,
        signedContent: String,
        expectedSignatureHex: String,
    ): Boolean {
        val computed = hexHmacSha256(secret, signedContent)
        return MessageDigest.isEqual(
            computed.toByteArray(Charsets.UTF_8),
            expectedSignatureHex.toByteArray(Charsets.UTF_8),
        )
    }
}
