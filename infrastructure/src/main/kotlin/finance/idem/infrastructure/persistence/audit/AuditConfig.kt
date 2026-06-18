package finance.idem.infrastructure.persistence.audit

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(AuditProperties::class)
class AuditConfig
