package finance.idem.sdk

import finance.idem.TestcontainersConfiguration
import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope
import finance.idem.infrastructure.security.ApiKeyService
import finance.idem.sdk.exception.ApiException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class IdemClientE2ETest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var apiKeyService: ApiKeyService

    private fun idemClient(apiKey: String) = IdemClient(baseUrl = "http://localhost:$port", apiKey = apiKey)

    @Test
    fun `getBalance for unknown account maps 404 to ApiException carrying traceId from X-Idem-Trace-Id header`() = runBlocking {
        val (rawKey, _) = apiKeyService.generate(TenantId.generate(), setOf(ApiScope.ACCOUNTS_READ))
        val client = idemClient(rawKey)

        val exception = assertFailsWith<ApiException> {
            client.getBalance(UUID.randomUUID().toString())
        }

        assertEquals(404, exception.statusCode)
        assertEquals("NOT_FOUND", exception.errorCode)
        assertNotNull(exception.traceId)
        UUID.fromString(exception.traceId)

        client.close()
    }
}
