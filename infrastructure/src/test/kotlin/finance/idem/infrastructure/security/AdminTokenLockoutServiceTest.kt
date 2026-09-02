package finance.idem.infrastructure.security

import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AdminTokenLockoutServiceTest : SharedPostgresTestBase() {
    companion object {
        // Redis has no module-wide singleton — mirrors ApiKeyServiceIntegrationTest.
        @Container
        val redis: GenericContainer<*> =
            GenericContainer("redis:7")
                .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }

    @Autowired
    private lateinit var lockoutService: AdminTokenLockoutService

    private fun freshIp() = "10.0.0.${UUID.randomUUID().hashCode() and 0xff}-${UUID.randomUUID()}"

    @Test
    fun `allows attempts under the threshold`() {
        val ip = freshIp()

        repeat(4) { lockoutService.recordFailure(ip) }

        assertFalse(lockoutService.isLockedOut(ip))
    }

    @Test
    fun `locks out once the failure threshold is reached`() {
        val ip = freshIp()

        repeat(5) { lockoutService.recordFailure(ip) }

        assertTrue(lockoutService.isLockedOut(ip))
    }

    @Test
    fun `lockout is scoped per client IP`() {
        val lockedIp = freshIp()
        val otherIp = freshIp()

        repeat(5) { lockoutService.recordFailure(lockedIp) }

        assertTrue(lockoutService.isLockedOut(lockedIp))
        assertFalse(lockoutService.isLockedOut(otherIp))
    }
}
