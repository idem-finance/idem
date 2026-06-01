package finance.idem.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration
class PersistenceTestConfig {
    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()
}
