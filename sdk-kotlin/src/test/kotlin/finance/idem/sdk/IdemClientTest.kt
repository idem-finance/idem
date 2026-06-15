package finance.idem.sdk

import finance.idem.sdk.exception.ApiException
import finance.idem.sdk.exception.NetworkException
import finance.idem.sdk.exception.RateLimitException
import finance.idem.sdk.http.defaultHttpClient
import finance.idem.sdk.model.EntryType
import finance.idem.sdk.model.FiatCurrency
import finance.idem.sdk.model.FiatEntryRequest
import finance.idem.sdk.model.FiatEntryResponse
import finance.idem.sdk.model.JournalLineRequest
import finance.idem.sdk.model.OnChainEntryRequest
import finance.idem.sdk.model.OnChainEntryResponse
import finance.idem.sdk.model.PaymentRail
import finance.idem.sdk.model.PostTransactionRequest
import finance.idem.sdk.model.StablecoinToken
import finance.idem.sdk.model.ChainId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdemClientTest {

    private fun clientWith(baseUrl: String = "http://localhost", handler: MockRequestHandler): IdemClient {
        val httpClient = defaultHttpClient(MockEngine(handler))
        return IdemClient(baseUrl = baseUrl, apiKey = "sk_live_test", httpClient = httpClient)
    }

    private suspend fun HttpRequestData.bodyAsString(): String {
        val content = body
        return when (content) {
            is OutgoingContent.ByteArrayContent -> String(content.bytes(), Charsets.UTF_8)
            is OutgoingContent.WriteChannelContent -> {
                val channel = ByteChannel()
                coroutineScope {
                    launch(Dispatchers.Unconfined) {
                        content.writeTo(channel)
                        channel.close(null)
                    }
                }
                String(channel.readRemaining().readBytes(), Charsets.UTF_8)
            }
            else -> ""
        }
    }

    private fun sampleRequest(): PostTransactionRequest = PostTransactionRequest(
        lines = listOf(
            JournalLineRequest(
                accountId = UUID.randomUUID(),
                entryType = EntryType.DEBIT,
                monetaryEntry = FiatEntryRequest(
                    amount = BigDecimal("100.00"),
                    currency = FiatCurrency.BRL,
                    rail = PaymentRail.PIX,
                ),
            ),
            JournalLineRequest(
                accountId = UUID.randomUUID(),
                entryType = EntryType.CREDIT,
                monetaryEntry = FiatEntryRequest(
                    amount = BigDecimal("100.00"),
                    currency = FiatCurrency.BRL,
                    rail = PaymentRail.PIX,
                ),
            ),
        ),
    )

    // ---- construction ----

    @Test
    fun `baseUrl trailing slash is trimmed`() {
        val client = IdemClient(baseUrl = "http://host/", apiKey = "sk_live_test")
        assertEquals("http://host", client.baseUrl)
        client.close()
    }

    // ---- postTransaction ----

    @Test
    fun `postTransaction returns transactionId on 201 and sends api key header`() = runTest {
        val transactionId = UUID.randomUUID()
        var captured: HttpRequestData? = null
        val client = clientWith { request ->
            captured = request
            respond(
                content = ByteReadChannel("""{"transactionId":"$transactionId"}"""),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val response = client.postTransaction(sampleRequest())

        assertEquals(transactionId, response.transactionId)
        assertEquals("sk_live_test", captured!!.headers["X-API-Key"])
    }

    @Test
    fun `postTransaction auto-generates a valid Idempotency-Key when not provided`() = runTest {
        var captured: HttpRequestData? = null
        val client = clientWith { request ->
            captured = request
            respond(
                content = ByteReadChannel("""{"transactionId":"${UUID.randomUUID()}"}"""),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        client.postTransaction(sampleRequest())

        val idempotencyKey = captured!!.headers["Idempotency-Key"]
        assertNotNullUuid(idempotencyKey)
    }

    @Test
    fun `postTransaction passes through an explicit idempotencyKey verbatim`() = runTest {
        var captured: HttpRequestData? = null
        val client = clientWith { request ->
            captured = request
            respond(
                content = ByteReadChannel("""{"transactionId":"${UUID.randomUUID()}"}"""),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        client.postTransaction(sampleRequest(), idempotencyKey = "my-fixed-key")

        assertEquals("my-fixed-key", captured!!.headers["Idempotency-Key"])
    }

    @Test
    fun `postTransaction serializes sealed MonetaryEntryRequest with type discriminator`() = runTest {
        var captured: HttpRequestData? = null
        val client = clientWith { request ->
            captured = request
            respond(
                content = ByteReadChannel("""{"transactionId":"${UUID.randomUUID()}"}"""),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val request = PostTransactionRequest(
            lines = listOf(
                JournalLineRequest(
                    accountId = UUID.randomUUID(),
                    entryType = EntryType.DEBIT,
                    monetaryEntry = OnChainEntryRequest(
                        amount = BigDecimal("50.00"),
                        token = StablecoinToken.USDC,
                        chainId = ChainId.EVM,
                        txHash = "0xabc",
                        blockNumber = 123L,
                        walletAddress = "0xwallet",
                        tokenContract = "0xcontract",
                    ),
                ),
                JournalLineRequest(
                    accountId = UUID.randomUUID(),
                    entryType = EntryType.CREDIT,
                    monetaryEntry = FiatEntryRequest(
                        amount = BigDecimal("50.00"),
                        currency = FiatCurrency.USD,
                        rail = PaymentRail.WIRE,
                    ),
                ),
            ),
        )

        client.postTransaction(request)

        val body = captured!!.bodyAsString()
        assertTrue(body.contains("\"type\":\"ONCHAIN\""))
        assertTrue(body.contains("\"type\":\"FIAT\""))
    }

    @Test
    fun `postTransaction maps 400 to ApiException`() = runTest {
        val client = clientWith {
            respond(
                content = ByteReadChannel("""{"code":"INVALID_REQUEST","message":"lines must be balanced"}"""),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val exception = assertFailsWith<ApiException> {
            client.postTransaction(sampleRequest())
        }
        assertEquals(400, exception.statusCode)
        assertEquals("INVALID_REQUEST", exception.errorCode)
        assertEquals("lines must be balanced", exception.message)
    }

    @Test
    fun `postTransaction maps 409 idempotency conflict to ApiException`() = runTest {
        val client = clientWith {
            respond(
                content = ByteReadChannel("""{"code":"IDEMPOTENCY_CONFLICT","message":"key already used with a different request"}"""),
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val exception = assertFailsWith<ApiException> {
            client.postTransaction(sampleRequest())
        }
        assertEquals(409, exception.statusCode)
        assertEquals("IDEMPOTENCY_CONFLICT", exception.errorCode)
    }

    @Test
    fun `postTransaction maps 422 invariant violation to ApiException`() = runTest {
        val client = clientWith {
            respond(
                content = ByteReadChannel("""{"code":"INVARIANT_VIOLATION","message":"debits must equal credits"}"""),
                status = HttpStatusCode.UnprocessableEntity,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val exception = assertFailsWith<ApiException> {
            client.postTransaction(sampleRequest())
        }
        assertEquals(422, exception.statusCode)
        assertEquals("INVARIANT_VIOLATION", exception.errorCode)
    }

    @Test
    fun `postTransaction maps 429 with Retry-After to RateLimitException`() = runTest {
        val client = clientWith {
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.RetryAfter, "30"),
            )
        }

        val exception = assertFailsWith<RateLimitException> {
            client.postTransaction(sampleRequest())
        }
        assertEquals(30, exception.retryAfterSeconds)
    }

    @Test
    fun `postTransaction maps 429 without Retry-After to RateLimitException with zero`() = runTest {
        val client = clientWith {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.TooManyRequests)
        }

        val exception = assertFailsWith<RateLimitException> {
            client.postTransaction(sampleRequest())
        }
        assertEquals(0, exception.retryAfterSeconds)
    }

    @Test
    fun `postTransaction maps 400 to ApiException with traceId from X-Idem-Trace-Id header`() = runTest {
        val client = clientWith {
            respond(
                content = ByteReadChannel("""{"code":"INVALID_REQUEST","message":"lines must be balanced"}"""),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "X-Idem-Trace-Id" to listOf("abc-123-trace"),
                ),
            )
        }

        val exception = assertFailsWith<ApiException> {
            client.postTransaction(sampleRequest())
        }
        assertEquals("abc-123-trace", exception.traceId)
    }

    @Test
    fun `postTransaction maps 400 to ApiException with null traceId when header absent`() = runTest {
        val client = clientWith {
            respond(
                content = ByteReadChannel("""{"code":"INVALID_REQUEST","message":"lines must be balanced"}"""),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val exception = assertFailsWith<ApiException> {
            client.postTransaction(sampleRequest())
        }
        assertNull(exception.traceId)
    }

    @Test
    fun `postTransaction maps 429 with Retry-After and X-Idem-Trace-Id to RateLimitException`() = runTest {
        val client = clientWith {
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(
                    HttpHeaders.RetryAfter to listOf("30"),
                    "X-Idem-Trace-Id" to listOf("xyz-789-trace"),
                ),
            )
        }

        val exception = assertFailsWith<RateLimitException> {
            client.postTransaction(sampleRequest())
        }
        assertEquals(30, exception.retryAfterSeconds)
        assertEquals("xyz-789-trace", exception.traceId)
    }

    @Test
    fun `postTransaction wraps transport failure as NetworkException`() = runTest {
        val client = clientWith {
            throw IOException("Connection refused")
        }

        val exception = assertFailsWith<NetworkException> {
            client.postTransaction(sampleRequest())
        }
        assertTrue(exception.cause is IOException)
    }

    // ---- getBalance ----

    @Test
    fun `getBalance omits asOf query parameter when not provided`() = runTest {
        val accountId = UUID.randomUUID()
        var captured: HttpRequestData? = null
        val client = clientWith { request ->
            captured = request
            respond(
                content = ByteReadChannel(
                    """{"accountId":"$accountId","currency":"BRL","amount":100.00,"normalBalance":"CREDIT","computedAt":"2024-01-01T00:00:00Z"}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val response = client.getBalance(accountId.toString())

        assertEquals(accountId, response.accountId)
        assertEquals(BigDecimal("100.00"), response.amount)
        assertNull(captured!!.url.parameters["asOf"])
    }

    @Test
    fun `getBalance includes asOf query parameter when provided`() = runTest {
        val accountId = UUID.randomUUID()
        val asOf = java.time.Instant.parse("2024-01-15T00:00:00Z")
        var captured: HttpRequestData? = null
        val client = clientWith { request ->
            captured = request
            respond(
                content = ByteReadChannel(
                    """{"accountId":"$accountId","currency":"BRL","amount":100.00,"normalBalance":"CREDIT","computedAt":"2024-01-01T00:00:00Z"}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        client.getBalance(accountId.toString(), asOf = asOf)

        assertEquals(asOf.toString(), captured!!.url.parameters["asOf"])
    }

    @Test
    fun `getBalance maps empty-body 404 to NOT_FOUND ApiException`() = runTest {
        val client = clientWith {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.NotFound)
        }

        val exception = assertFailsWith<ApiException> {
            client.getBalance(UUID.randomUUID().toString())
        }
        assertEquals(404, exception.statusCode)
        assertEquals("NOT_FOUND", exception.errorCode)
        assertEquals("Resource not found", exception.message)
    }

    @Test
    fun `getBalance maps empty-body 503 to UNKNOWN_ERROR ApiException`() = runTest {
        val client = clientWith {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.ServiceUnavailable)
        }

        val exception = assertFailsWith<ApiException> {
            client.getBalance(UUID.randomUUID().toString())
        }
        assertEquals(503, exception.statusCode)
        assertEquals("UNKNOWN_ERROR", exception.errorCode)
        assertEquals("Unexpected error response from server", exception.message)
    }

    @Test
    fun `getBalance request URL has no double slash when baseUrl has trailing slash`() = runTest {
        val accountId = UUID.randomUUID()
        var captured: HttpRequestData? = null
        val client = clientWith(baseUrl = "http://localhost/") { request ->
            captured = request
            respond(
                content = ByteReadChannel(
                    """{"accountId":"$accountId","currency":"BRL","amount":100.00,"normalBalance":"CREDIT","computedAt":"2024-01-01T00:00:00Z"}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        client.getBalance(accountId.toString())

        assertEquals("/api/v1/accounts/$accountId/balance", captured!!.url.encodedPath)
    }

    // ---- listEntries ----

    @Test
    fun `listEntries sends default limit and omits optional params`() = runTest {
        val accountId = UUID.randomUUID()
        var captured: HttpRequestData? = null
        val client = clientWith { request ->
            captured = request
            respond(
                content = ByteReadChannel("""{"accountId":"$accountId","entries":[],"nextCursor":null}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        client.listEntries(accountId.toString())

        val params = captured!!.url.parameters
        assertEquals("50", params["limit"])
        assertNull(params["from"])
        assertNull(params["to"])
        assertNull(params["cursor"])
    }

    @Test
    fun `listEntries sends all optional params when provided`() = runTest {
        val accountId = UUID.randomUUID()
        val from = java.time.Instant.parse("2024-01-01T00:00:00Z")
        val to = java.time.Instant.parse("2024-01-31T00:00:00Z")
        var captured: HttpRequestData? = null
        val client = clientWith { request ->
            captured = request
            respond(
                content = ByteReadChannel("""{"accountId":"$accountId","entries":[],"nextCursor":null}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        client.listEntries(accountId.toString(), from = from, to = to, limit = 10, cursor = "cursor-1")

        val params = captured!!.url.parameters
        assertEquals("10", params["limit"])
        assertEquals(from.toString(), params["from"])
        assertEquals(to.toString(), params["to"])
        assertEquals("cursor-1", params["cursor"])
    }

    @Test
    fun `listEntries deserializes mixed FiatEntryResponse and OnChainEntryResponse`() = runTest {
        val accountId = UUID.randomUUID()
        val entryId1 = UUID.randomUUID()
        val entryId2 = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val client = clientWith {
            respond(
                content = ByteReadChannel(
                    """
                    {
                      "accountId":"$accountId",
                      "entries":[
                        {
                          "entryId":"$entryId1",
                          "transactionId":"$transactionId",
                          "type":"DEBIT",
                          "monetary":{"type":"FIAT","amount":100.00,"currency":"BRL","rail":"PIX"},
                          "createdAt":"2024-01-01T00:00:00Z"
                        },
                        {
                          "entryId":"$entryId2",
                          "transactionId":"$transactionId",
                          "type":"CREDIT",
                          "monetary":{"type":"ONCHAIN","amount":50.00,"token":"USDC","chainId":"EVM","txHash":"0xabc","blockNumber":123,"walletAddress":"0xwallet","tokenContract":"0xcontract"},
                          "createdAt":"2024-01-02T00:00:00Z"
                        }
                      ],
                      "nextCursor":null
                    }
                    """.trimIndent(),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val page = client.listEntries(accountId.toString())

        assertEquals(2, page.entries.size)
        val fiat = page.entries[0].monetary
        assertTrue(fiat is FiatEntryResponse)
        assertEquals(FiatCurrency.BRL, fiat.currency)
        val onChain = page.entries[1].monetary
        assertTrue(onChain is OnChainEntryResponse)
        assertEquals(StablecoinToken.USDC, onChain.token)
        assertNull(onChain.fromAddress)
    }

    @Test
    fun `listEntries maps 400 with ErrorResponse body to ApiException`() = runTest {
        val client = clientWith {
            respond(
                content = ByteReadChannel("""{"code":"INVALID_LIMIT","message":"limit must be between 1 and 100"}"""),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val exception = assertFailsWith<ApiException> {
            client.listEntries(UUID.randomUUID().toString())
        }
        assertEquals(400, exception.statusCode)
        assertEquals("INVALID_LIMIT", exception.errorCode)
    }

    // ---- getStatement ----

    @Test
    fun `getStatement always sends from and to query params`() = runTest {
        val accountId = UUID.randomUUID()
        val from = java.time.Instant.parse("2024-01-01T00:00:00Z")
        val to = java.time.Instant.parse("2024-01-31T00:00:00Z")
        var captured: HttpRequestData? = null
        val client = clientWith { request ->
            captured = request
            respond(
                content = ByteReadChannel(
                    """{"accountId":"$accountId","currency":"BRL","from":"$from","to":"$to","openingBalance":100.00,"closingBalance":100.00,"movements":[]}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        client.getStatement(accountId.toString(), from, to)

        assertEquals(from.toString(), captured!!.url.parameters["from"])
        assertEquals(to.toString(), captured!!.url.parameters["to"])
    }

    @Test
    fun `getStatement deserializes movements`() = runTest {
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val from = java.time.Instant.parse("2024-01-01T00:00:00Z")
        val to = java.time.Instant.parse("2024-01-31T00:00:00Z")
        val client = clientWith {
            respond(
                content = ByteReadChannel(
                    """
                    {
                      "accountId":"$accountId",
                      "currency":"BRL",
                      "from":"$from",
                      "to":"$to",
                      "openingBalance":100.00,
                      "closingBalance":150.00,
                      "movements":[
                        {"transactionId":"$transactionId","type":"CREDIT","amount":50.00,"description":"deposit","occurredAt":"2024-01-15T00:00:00Z"}
                      ]
                    }
                    """.trimIndent(),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val statement = client.getStatement(accountId.toString(), from, to)

        assertEquals(BigDecimal("100.00"), statement.openingBalance)
        assertEquals(BigDecimal("150.00"), statement.closingBalance)
        assertEquals(1, statement.movements.size)
        assertEquals(EntryType.CREDIT, statement.movements[0].type)
        assertEquals("deposit", statement.movements[0].description)
    }

    @Test
    fun `getStatement maps empty-body 404 to NOT_FOUND ApiException`() = runTest {
        val client = clientWith {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.NotFound)
        }

        val exception = assertFailsWith<ApiException> {
            client.getStatement(UUID.randomUUID().toString(), java.time.Instant.now(), java.time.Instant.now())
        }
        assertEquals(404, exception.statusCode)
        assertEquals("NOT_FOUND", exception.errorCode)
    }

    // ---- close ----

    @Test
    fun `close closes the underlying httpClient`() {
        val client = clientWith {
            respond(
                content = ByteReadChannel("""{"transactionId":"${UUID.randomUUID()}"}"""),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        client.close()

        assertFalse(client.httpClient.coroutineContext[Job]!!.isActive)
    }

    private fun assertNotNullUuid(value: String?) {
        requireNotNull(value)
        UUID.fromString(value)
    }
}