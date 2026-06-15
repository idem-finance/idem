package finance.idem.infrastructure.observability

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TraceIdFilterConfig {
    @Bean
    fun traceIdFilter(): TraceIdFilter = TraceIdFilter()
}
