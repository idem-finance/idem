package finance.idem

import finance.idem.application.port.AuditRepository
import finance.idem.application.port.WebhookOutboxRepository
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class IdemApplicationTests {

    // Adapters for these ports live on branches not yet merged to main.
    // Mocked here so the full context loads as incremental PRs are reviewed.
    @MockitoBean
    lateinit var auditRepository: AuditRepository

    @MockitoBean
    lateinit var webhookOutboxRepository: WebhookOutboxRepository

    @Test
    fun contextLoads() {
    }

}
