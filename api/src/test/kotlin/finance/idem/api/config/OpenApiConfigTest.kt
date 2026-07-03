package finance.idem.api.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenApiConfigTest {
    @Test
    fun `idemOpenAPI bean carries the expected API metadata`() {
        val openAPI = OpenApiConfig().idemOpenAPI()

        assertEquals("Idem Ledger API", openAPI.info.title)
        assertEquals("0.1.0", openAPI.info.version)
        assertEquals("Double-entry ledger for stablecoin payments", openAPI.info.description)
    }
}
