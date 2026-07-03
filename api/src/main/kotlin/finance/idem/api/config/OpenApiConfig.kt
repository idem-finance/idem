package finance.idem.api.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun idemOpenAPI(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Idem Ledger API")
                    .version("0.1.0")
                    .description("Double-entry ledger for stablecoin payments"),
            )
}
