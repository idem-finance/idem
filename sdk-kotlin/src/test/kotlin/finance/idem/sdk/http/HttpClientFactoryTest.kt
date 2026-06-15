package finance.idem.sdk.http

import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpClientFactoryTest {

    data class Sample(val id: String, val createdAt: Instant)

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

    @Test
    fun `defaultHttpClient serializes Instant as ISO-8601 string via JavaTimeModule`() = runTest {
        var captured: HttpRequestData? = null
        val client = defaultHttpClient(MockEngine { request ->
            captured = request
            respond(
                content = ByteReadChannel("{}"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })

        client.post("http://localhost/echo") {
            contentType(ContentType.Application.Json)
            setBody(Sample(id = "abc", createdAt = Instant.parse("2024-01-15T10:30:00Z")))
        }

        assertTrue(captured!!.bodyAsString().contains("\"2024-01-15T10:30:00Z\""))
        client.close()
    }

    @Test
    fun `defaultHttpClient deserializes a Kotlin data class response body`() = runTest {
        val client = defaultHttpClient(MockEngine {
            respond(
                content = ByteReadChannel("""{"id":"abc","createdAt":"2024-01-15T10:30:00Z"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })

        val result: Sample = client.get("http://localhost/sample").body()

        assertEquals("abc", result.id)
        assertEquals(Instant.parse("2024-01-15T10:30:00Z"), result.createdAt)
        client.close()
    }
}
