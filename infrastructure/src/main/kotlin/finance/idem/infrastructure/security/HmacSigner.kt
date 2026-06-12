package finance.idem.infrastructure.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HmacSigner {
    fun hexHmacSha256(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
