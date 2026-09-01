package finance.idem.infrastructure.outbox

import finance.idem.application.outbox.WebhookOutboxDispatch
import finance.idem.application.port.TenantRepository
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.tenant.TenantWebhookConfig
import finance.idem.application.usage.UsageMeteringService
import finance.idem.core.TenantId
import finance.idem.core.usage.MetricType
import finance.idem.infrastructure.security.HmacSigner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebhookOutboxPollerTest {
    private lateinit var webhookOutboxRepository: WebhookOutboxRepository
    private lateinit var tenantRepository: TenantRepository
    private lateinit var httpClient: HttpClient
    private lateinit var urlValidator: WebhookUrlValidator
    private lateinit var usageMeteringService: UsageMeteringService

    private val tenantId = TenantId.generate()
    private val webhookConfig = TenantWebhookConfig(webhookUrl = "https://example.com/webhook", webhookSecret = "test-secret")

    @BeforeEach
    fun setUp() {
        webhookOutboxRepository = mock()
        tenantRepository = mock()
        httpClient = mock()
        urlValidator = WebhookUrlValidator { Result.success(Unit) }
        usageMeteringService = mock()
    }

    private fun poller(maxAttempts: Int = 5) =
        WebhookOutboxPoller(
            webhookOutboxRepository = webhookOutboxRepository,
            tenantRepository = tenantRepository,
            httpClient = httpClient,
            urlValidator = urlValidator,
            usageMeteringService = usageMeteringService,
            timeoutMs = 5000,
            maxAttempts = maxAttempts,
            batchSize = 50,
        )

    private fun dispatch(
        attempts: Int = 0,
        tenant: TenantId = tenantId,
    ) = WebhookOutboxDispatch(
        id = UUID.randomUUID(),
        tenantId = tenant,
        eventType = "transaction.committed",
        payload = """{"event":"transaction.committed"}""",
        attempts = attempts,
    )

    private fun httpResponse(statusCode: Int): HttpResponse<String> {
        val response = mock<HttpResponse<String>>()
        whenever(response.statusCode()).thenReturn(statusCode)
        return response
    }

    @Test
    fun `delivers successfully on HTTP 200`() {
        val entry = dispatch()
        whenever(webhookOutboxRepository.findDispatchable(50)).thenReturn(listOf(entry))
        whenever(tenantRepository.findWebhookConfig(tenantId)).thenReturn(webhookConfig)
        val response = httpResponse(200)
        whenever(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(response)

        poller().poll()

        verify(webhookOutboxRepository).markDelivered(entry.id, tenantId)
        verify(webhookOutboxRepository, never()).markFailedForRetry(any(), any(), any(), any(), any())
        verify(webhookOutboxRepository, never()).markDead(any(), any(), any())
        verify(usageMeteringService).recordUsage(tenantId, MetricType.WEBHOOK_DELIVERY_COUNT)
    }

    @Test
    fun `leaves row PENDING when tenant has no webhook configured`() {
        val entry = dispatch()
        whenever(webhookOutboxRepository.findDispatchable(50)).thenReturn(listOf(entry))
        whenever(tenantRepository.findWebhookConfig(tenantId)).thenReturn(null)

        poller().poll()

        verifyNoInteractions(httpClient)
        verify(webhookOutboxRepository, never()).markDelivered(any(), any())
        verify(webhookOutboxRepository, never()).markFailedForRetry(any(), any(), any(), any(), any())
        verify(webhookOutboxRepository, never()).markDead(any(), any(), any())
    }

    @Test
    fun `a failing findWebhookConfig is caught and does not propagate`() {
        val entry = dispatch()
        whenever(webhookOutboxRepository.findDispatchable(50)).thenReturn(listOf(entry))
        whenever(tenantRepository.findWebhookConfig(tenantId)).thenThrow(RuntimeException("db down"))

        poller().poll()

        verifyNoInteractions(httpClient)
        verify(webhookOutboxRepository, never()).markDelivered(any(), any())
        verify(webhookOutboxRepository, never()).markFailedForRetry(any(), any(), any(), any(), any())
        verify(webhookOutboxRepository, never()).markDead(any(), any(), any())
    }

    @Test
    fun `non-2xx response schedules a retry with backoff for attempt 1`() {
        val entry = dispatch(attempts = 0)
        whenever(webhookOutboxRepository.findDispatchable(50)).thenReturn(listOf(entry))
        whenever(tenantRepository.findWebhookConfig(tenantId)).thenReturn(webhookConfig)
        val response = httpResponse(500)
        whenever(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(response)

        val before = Instant.now()
        poller().poll()

        val nextRetryAtCaptor = argumentCaptor<Instant>()
        // tenantId is a @JvmInline value class -- at this non-generic parameter
        // position it is unboxed to a raw UUID at the JVM level, so eq(tenantId)
        // (which wraps a boxed TenantId) never matches. Use any() here; the
        // success-path test verifies the tenantId argument via a direct
        // (non-matcher) call to markDelivered.
        verify(webhookOutboxRepository).markFailedForRetry(eq(entry.id), any(), eq(1), nextRetryAtCaptor.capture(), eq("HTTP 500"))
        val nextRetryAt = nextRetryAtCaptor.firstValue
        assertTrue(nextRetryAt.isAfter(before.plusSeconds(4)))
        assertTrue(nextRetryAt.isBefore(before.plusSeconds(6)))
        verify(webhookOutboxRepository, never()).markDead(any(), any(), any())
    }

    @Test
    fun `httpClient send throwing schedules a retry with the exception message`() {
        val entry = dispatch(attempts = 0)
        whenever(webhookOutboxRepository.findDispatchable(50)).thenReturn(listOf(entry))
        whenever(tenantRepository.findWebhookConfig(tenantId)).thenReturn(webhookConfig)
        whenever(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenThrow(IOException("connect timed out"))

        poller().poll()

        // see comment in "non-2xx response..." -- tenantId can't be matched via eq()
        verify(webhookOutboxRepository).markFailedForRetry(eq(entry.id), any(), eq(1), any(), eq("connect timed out"))
    }

    @Test
    fun `attempt reaching maxAttempts marks the row DEAD instead of retrying`() {
        val entry = dispatch(attempts = 4)
        whenever(webhookOutboxRepository.findDispatchable(50)).thenReturn(listOf(entry))
        whenever(tenantRepository.findWebhookConfig(tenantId)).thenReturn(webhookConfig)
        val response = httpResponse(500)
        whenever(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(response)

        poller().poll()

        verify(webhookOutboxRepository).markDead(entry.id, tenantId, "HTTP 500")
        verify(webhookOutboxRepository, never()).markFailedForRetry(any(), any(), any(), any(), any())
    }

    @Test
    fun `no dispatchable rows -- no further calls`() {
        whenever(webhookOutboxRepository.findDispatchable(50)).thenReturn(emptyList())

        poller().poll()

        verifyNoInteractions(tenantRepository)
        verifyNoInteractions(httpClient)
    }

    @Test
    fun `findDispatchable throwing is caught and does not propagate`() {
        whenever(webhookOutboxRepository.findDispatchable(50)).thenThrow(RuntimeException("db down"))

        poller().poll()

        verifyNoInteractions(tenantRepository)
        verifyNoInteractions(httpClient)
    }

    @Test
    fun `constructor rejects maxAttempts above RetrySchedule's supported range`() {
        assertFailsWith<IllegalArgumentException> { poller(maxAttempts = RetrySchedule.MAX_SUPPORTED_ATTEMPTS + 1) }
    }

    @Test
    fun `constructor rejects maxAttempts below 1`() {
        assertFailsWith<IllegalArgumentException> { poller(maxAttempts = 0) }
    }

    @Test
    fun `constructor accepts maxAttempts at the lower boundary`() {
        poller(maxAttempts = 1)
    }

    @Test
    fun `SSRF-blocked URL marks the row DEAD immediately and skips httpClient`() {
        val entry = dispatch()
        val ssrfConfig = TenantWebhookConfig(webhookUrl = "http://169.254.169.254/latest/meta-data/", webhookSecret = "secret")
        whenever(webhookOutboxRepository.findDispatchable(50)).thenReturn(listOf(entry))
        whenever(tenantRepository.findWebhookConfig(tenantId)).thenReturn(ssrfConfig)
        urlValidator = WebhookUrlValidator { Result.failure(IllegalArgumentException("link-local address")) }

        poller().poll()

        verifyNoInteractions(httpClient)
        verify(webhookOutboxRepository).markDead(
            eq(entry.id),
            any(),
            org.mockito.kotlin.argThat { contains("SSRF_BLOCKED") },
        )
        verify(webhookOutboxRepository, never()).markDelivered(any(), any())
        verify(webhookOutboxRepository, never()).markFailedForRetry(any(), any(), any(), any(), any())
    }

    @Test
    fun `request is signed with the resolved tenant's secret`() {
        val entry = dispatch()
        whenever(webhookOutboxRepository.findDispatchable(50)).thenReturn(listOf(entry))
        whenever(tenantRepository.findWebhookConfig(tenantId)).thenReturn(webhookConfig)
        val response = httpResponse(200)
        whenever(httpClient.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(response)

        poller().poll()

        val requestCaptor = argumentCaptor<HttpRequest>()
        verify(httpClient).send(requestCaptor.capture(), any<HttpResponse.BodyHandler<String>>())

        val expectedSignature = "sha256=" + HmacSigner.hexHmacSha256(webhookConfig.webhookSecret, entry.payload)
        val actualSignature =
            requestCaptor.firstValue
                .headers()
                .firstValue("X-Idem-Signature")
                .orElse(null)
        assertEquals(expectedSignature, actualSignature)
        assertEquals(webhookConfig.webhookUrl, requestCaptor.firstValue.uri().toString())
    }
}
