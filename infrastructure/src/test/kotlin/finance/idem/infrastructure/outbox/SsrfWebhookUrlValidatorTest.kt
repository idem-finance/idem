package finance.idem.infrastructure.outbox

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SsrfWebhookUrlValidatorTest {

    private val validator = SsrfWebhookUrlValidator(requireHttps = true)
    private val devValidator = SsrfWebhookUrlValidator(requireHttps = false)

    // 93.184.216.34 is example.com's IANA-managed public IP — no DNS needed
    private val publicHttpsUrl = "https://93.184.216.34/webhook"
    private val publicHttpUrl = "http://93.184.216.34/webhook"

    @Test
    fun `valid https URL with public IP passes`() {
        assertTrue(validator.validate(publicHttpsUrl).isSuccess)
    }

    @Test
    fun `http URL rejected when requireHttps is true`() {
        val result = validator.validate(publicHttpUrl)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("https"))
    }

    @Test
    fun `http URL with public IP allowed when requireHttps is false`() {
        assertTrue(devValidator.validate(publicHttpUrl).isSuccess)
    }

    @Test
    fun `IMDS link-local address 169_254_169_254 is rejected`() {
        assertTrue(validator.validate("https://169.254.169.254/latest/meta-data/").isFailure)
    }

    @Test
    fun `loopback 127_0_0_1 is rejected`() {
        assertTrue(validator.validate("https://127.0.0.1/webhook").isFailure)
    }

    @Test
    fun `RFC-1918 10_x range is rejected`() {
        assertTrue(validator.validate("https://10.0.0.1/webhook").isFailure)
    }

    @Test
    fun `RFC-1918 172_16_x range is rejected`() {
        assertTrue(validator.validate("https://172.16.0.1/webhook").isFailure)
    }

    @Test
    fun `RFC-1918 192_168_x range is rejected`() {
        assertTrue(validator.validate("https://192.168.1.1/webhook").isFailure)
    }

    @Test
    fun `localhost hostname is rejected`() {
        assertTrue(validator.validate("https://localhost/webhook").isFailure)
    }

    @Test
    fun `dot-internal hostname is rejected`() {
        assertTrue(validator.validate("https://payment-svc.internal/webhook").isFailure)
    }

    @Test
    fun `dot-local hostname is rejected`() {
        assertTrue(validator.validate("https://payment-svc.local/webhook").isFailure)
    }

    @Test
    fun `dot-localhost hostname is rejected`() {
        assertTrue(validator.validate("https://anything.localhost/webhook").isFailure)
    }

    @Test
    fun `ftp scheme is rejected regardless of requireHttps flag`() {
        assertTrue(validator.validate("ftp://93.184.216.34/webhook").isFailure)
        assertTrue(devValidator.validate("ftp://93.184.216.34/webhook").isFailure)
    }

    @Test
    fun `malformed URL is rejected`() {
        assertTrue(validator.validate("not a url at all").isFailure)
    }

    @Test
    fun `private IP is still rejected even when requireHttps is false`() {
        assertFalse(devValidator.validate("http://10.0.0.1/webhook").isSuccess)
        assertFalse(devValidator.validate("http://169.254.169.254/latest/meta-data/").isSuccess)
    }
}
