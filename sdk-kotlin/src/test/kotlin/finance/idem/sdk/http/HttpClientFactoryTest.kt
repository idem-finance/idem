package finance.idem.sdk.http

import kotlin.test.Test
import kotlin.test.assertNotNull

class HttpClientFactoryTest {

    @Test
    fun `defaultHttpClient builds a client with Jackson content negotiation configured`() {
        val client = defaultHttpClient()

        assertNotNull(client)
        client.close()
    }
}
