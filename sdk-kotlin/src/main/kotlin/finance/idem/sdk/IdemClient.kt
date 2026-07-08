package finance.idem.sdk

import finance.idem.sdk.exception.ApiException
import finance.idem.sdk.exception.NetworkException
import finance.idem.sdk.exception.RateLimitException
import finance.idem.sdk.http.defaultHttpClient
import finance.idem.sdk.model.BalanceResponse
import finance.idem.sdk.model.CreateAccountRequest
import finance.idem.sdk.model.CreateAccountResponse
import finance.idem.sdk.model.EntriesPage
import finance.idem.sdk.model.ErrorResponse
import finance.idem.sdk.model.PostTransactionRequest
import finance.idem.sdk.model.ReconcileBatchItemResponse
import finance.idem.sdk.model.ReconcileBatchRequest
import finance.idem.sdk.model.RegisterSettlementRequest
import finance.idem.sdk.model.SettlementListResponse
import finance.idem.sdk.model.SettlementResponse
import finance.idem.sdk.model.StatementResponse
import finance.idem.sdk.model.TransactionResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.time.Instant
import java.util.UUID

/**
 * HTTP client for the Idem ledger REST API.
 *
 * Covers accounts, transactions, balances/entries/statements, reconciliation, and settlements.
 *
 * Every request carries the API key in the `X-API-Key` header. Non-2xx responses are mapped
 * to [ApiException]/[RateLimitException]; transport-level failures (connection refused,
 * timeouts, DNS) are wrapped as [NetworkException].
 */
class IdemClient(
    baseUrl: String,
    val apiKey: String,
    val httpClient: HttpClient = defaultHttpClient(),
) : Closeable {
    companion object {
        // Mirrored in infrastructure/src/main/kotlin/finance/idem/infrastructure/observability/TraceIdFilter.kt
        // (TraceIdFilter.TRACE_ID_HEADER) — duplicated as a literal because sdk-kotlin has zero
        // dependencies on other repo modules (see sdk-kotlin/pom.xml).
        private const val TRACE_ID_HEADER = "X-Idem-Trace-Id"
    }

    val baseUrl: String = baseUrl.trimEnd('/')

    override fun close() = httpClient.close()

    suspend fun postTransaction(
        request: PostTransactionRequest,
        idempotencyKey: String? = null,
    ): TransactionResponse {
        val response =
            executeRequest {
                httpClient.post("$baseUrl/api/v1/transactions") {
                    header("X-API-Key", apiKey)
                    header("Idempotency-Key", idempotencyKey ?: UUID.randomUUID().toString())
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            }
        return handleResponse(response)
    }

    suspend fun getBalance(
        accountId: String,
        asOf: Instant? = null,
    ): BalanceResponse {
        val response =
            executeRequest {
                httpClient.get("$baseUrl/api/v1/accounts/$accountId/balance") {
                    header("X-API-Key", apiKey)
                    asOf?.let { parameter("asOf", it.toString()) }
                }
            }
        return handleResponse(response)
    }

    suspend fun listEntries(
        accountId: String,
        from: Instant? = null,
        to: Instant? = null,
        limit: Int = 50,
        cursor: String? = null,
    ): EntriesPage {
        val response =
            executeRequest {
                httpClient.get("$baseUrl/api/v1/accounts/$accountId/entries") {
                    header("X-API-Key", apiKey)
                    from?.let { parameter("from", it.toString()) }
                    to?.let { parameter("to", it.toString()) }
                    parameter("limit", limit)
                    cursor?.let { parameter("cursor", it) }
                }
            }
        return handleResponse(response)
    }

    suspend fun getStatement(
        accountId: String,
        from: Instant,
        to: Instant,
    ): StatementResponse {
        val response =
            executeRequest {
                httpClient.get("$baseUrl/api/v1/accounts/$accountId/statement") {
                    header("X-API-Key", apiKey)
                    parameter("from", from.toString())
                    parameter("to", to.toString())
                }
            }
        return handleResponse(response)
    }

    suspend fun createAccount(request: CreateAccountRequest): CreateAccountResponse {
        val response =
            executeRequest {
                httpClient.post("$baseUrl/api/v1/accounts") {
                    header("X-API-Key", apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            }
        return handleResponse(response)
    }

    suspend fun reconcileBatch(transactionIds: List<UUID>): List<ReconcileBatchItemResponse> {
        val response =
            executeRequest {
                httpClient.post("$baseUrl/api/v1/reconciliation/batch") {
                    header("X-API-Key", apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(ReconcileBatchRequest(transactionIds))
                }
            }
        return handleResponse(response)
    }

    suspend fun registerSettlement(
        request: RegisterSettlementRequest,
        idempotencyKey: String,
    ): SettlementResponse {
        val response =
            executeRequest {
                httpClient.post("$baseUrl/api/v1/settlements") {
                    header("X-API-Key", apiKey)
                    header("Idempotency-Key", idempotencyKey)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            }
        return handleResponse(response)
    }

    suspend fun getSettlement(settlementId: UUID): SettlementResponse {
        val response =
            executeRequest {
                httpClient.get("$baseUrl/api/v1/settlements/$settlementId") {
                    header("X-API-Key", apiKey)
                }
            }
        return handleResponse(response)
    }

    suspend fun listSettlements(
        status: String? = null,
        from: Instant? = null,
        to: Instant? = null,
        limit: Int = 50,
        cursor: String? = null,
    ): SettlementListResponse {
        val response =
            executeRequest {
                httpClient.get("$baseUrl/api/v1/settlements") {
                    header("X-API-Key", apiKey)
                    status?.let { parameter("status", it) }
                    from?.let { parameter("from", it.toString()) }
                    to?.let { parameter("to", it.toString()) }
                    parameter("limit", limit)
                    cursor?.let { parameter("cursor", it) }
                }
            }
        return handleResponse(response)
    }

    suspend fun cancelSettlement(settlementId: UUID): SettlementResponse {
        val response =
            executeRequest {
                httpClient.delete("$baseUrl/api/v1/settlements/$settlementId") {
                    header("X-API-Key", apiKey)
                }
            }
        return handleResponse(response)
    }

    private suspend fun executeRequest(block: suspend () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw NetworkException(e)
        }

    private suspend inline fun <reified T> handleResponse(response: HttpResponse): T {
        if (response.status.isSuccess()) {
            return response.body()
        }
        val traceId = response.headers[TRACE_ID_HEADER]
        if (response.status == HttpStatusCode.TooManyRequests) {
            val retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toIntOrNull() ?: 0
            throw RateLimitException(retryAfterSeconds = retryAfterSeconds, traceId = traceId)
        }
        val error =
            try {
                response.body<ErrorResponse>()
            } catch (e: Exception) {
                if (response.status == HttpStatusCode.NotFound) {
                    ErrorResponse(code = "NOT_FOUND", message = "Resource not found")
                } else {
                    ErrorResponse(code = "UNKNOWN_ERROR", message = "Unexpected error response from server")
                }
            }
        throw ApiException(
            statusCode = response.status.value,
            errorCode = error.code,
            message = error.message,
            traceId = traceId,
        )
    }
}
