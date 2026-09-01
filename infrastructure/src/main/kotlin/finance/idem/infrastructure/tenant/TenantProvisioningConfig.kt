package finance.idem.infrastructure.tenant

import finance.idem.infrastructure.email.EmailProperties
import finance.idem.infrastructure.security.AdminProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(AdminProperties::class, DashboardProperties::class, EmailProperties::class)
class TenantProvisioningConfig
