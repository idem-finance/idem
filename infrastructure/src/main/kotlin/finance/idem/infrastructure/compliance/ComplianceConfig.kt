package finance.idem.infrastructure.compliance

import finance.idem.application.compliance.TravelRuleValidator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ComplianceConfig {
    @Bean
    fun travelRuleValidator(): TravelRuleValidator = TravelRuleValidator()
}
