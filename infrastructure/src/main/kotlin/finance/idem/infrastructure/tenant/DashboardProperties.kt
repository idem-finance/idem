package finance.idem.infrastructure.tenant

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("idem.dashboard")
data class DashboardProperties(
    val baseUrl: String = "https://cloud.idem.finance",
)
