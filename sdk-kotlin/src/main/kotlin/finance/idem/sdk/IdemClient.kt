package finance.idem.sdk

import finance.idem.sdk.exception.ApiException
import finance.idem.sdk.exception.NetworkException
import finance.idem.sdk.exception.RateLimitException
import finance.idem.sdk.http.defaultHttpClient
import finance.idem.sdk.model.BalanceResponse
import finance.idem.sdk.model.EntriesPage
import finance.idem.sdk.model.ErrorResponse
import finance.idem.sdk.model.PostTransactionRequest
import finance.idem.sdk.model.StatementResponse
import finance.idem.sdk.model.TransactionResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import java.time.Instant
import java.util.UUID

/**
 * HTTP client for the Idem ledger REST API.
 *
 * Every request carries the API key in the `X-API-Key` header. Non-2xx responses are mapped
 * to [ApiException]/[RateLimitException]; transport-level failures (connection refused,
 * timeouts, DNS) are wrapped as [NetworkException].
 */
class IdemClient(
    val baseUrl: String,
    val apiKey: String,
    val httpClient: HttpClient = defaultHttpClient(),
) {

    suspend fun postTransaction(
        request: PostTransactionRequest,
        idempotencyKey: String? = null,
    ): TransactionResponse {
        val response = executeRequest {
            httpClient.post("$baseUrl/api/v1/transactions") {
                header("X-API-Key", apiKey)
                header("Idempotency-Key", idempotencyKey ?: UUID.randomUUID().toString())
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
        return handleResponse(response)
    }

    suspend fun getBalance(accountId: String, asOf: Instant? = null): BalanceResponse {
        val response = executeRequest {
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
        val response = executeRequest {
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

    suspend fun getStatement(accountId: String, from: Instant, to: Instant): StatementResponse {
        val response = executeRequest {
            httpClient.get("$baseUrl/api/v1/accounts/$accountId/statement") {
                header("X-API-Key", apiKey)
                parameter("from", from.toString())
                parameter("to", to.toString())
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
        if (response.status == HttpStatusCode.TooManyRequests) {
            val retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toIntOrNull() ?: 0
            throw RateLimitException(retryAfterSeconds = retryAfterSeconds)
        }
        val error = try {
            response.body<ErrorResponse>()
        } catch (e: Exception) {
            ErrorResponse(code = "NOT_FOUND", message = "Resource not found")
        }
        throw ApiException(
            statusCode = response.status.value,
            errorCode = error.code,
            message = error.message,
        )
    }
}