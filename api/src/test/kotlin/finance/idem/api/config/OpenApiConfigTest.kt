package finance.idem.api.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.info.BuildProperties
import java.util.Properties

class OpenApiConfigTest {
    @Test
    fun `idemOpenAPI bean falls back to a default version when BuildProperties is unavailable`() {
        val openAPI = OpenApiConfig(buildProperties = null).idemOpenAPI()

        assertEquals("Idem Ledger API", openAPI.info.title)
        assertEquals("0.1.0-SNAPSHOT", openAPI.info.version)
        assertEquals("Double-entry ledger for stablecoin payments", openAPI.info.description)
    }

    @Test
    fun `idemOpenAPI bean uses the Maven build version when BuildProperties is available`() {
        val buildProperties =
            BuildProperties(
                Properties().apply {
                    setProperty("version", "1.2.3")
                    setProperty("group", "finance.idem")
                    setProperty("artifact", "app")
                },
            )

        val openAPI = OpenApiConfig(buildProperties).idemOpenAPI()

        assertEquals("1.2.3", openAPI.info.version)
    }
}
