package finance.idem.infrastructure.telemetry

import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TenantThresholdWarnServiceIntegrationTest : SharedPostgresTestBase() {
    @Autowired
    lateinit var tenantThresholdWarnService: TenantThresholdWarnService

    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Test
    fun `TenantThresholdWarnService bean is present in context`() {
        assertNotNull(applicationContext.getBean(TenantThresholdWarnService::class.java))
    }

    @Test
    fun `checkTenantThreshold completes without error when no tenants exist`() {
        // zero tenants in DB — count(0) <= threshold(10) → no log, no exception
        tenantThresholdWarnService.checkTenantThreshold()
    }
}
