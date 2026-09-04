package finance.idem

import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope
import finance.idem.infrastructure.security.ApiKeyService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Activated only with the `seed` Spring profile. Creates a dev tenant and an ADMIN-scoped
 * API key, prints the raw key to stdout, then exits the process.
 *
 * Usage: ./mvnw spring-boot:run -pl app -Dspring-boot.run.profiles=dev,seed
 * Or via: make seed
 */
@Component
@Profile("seed")
class DevDataSeeder(
    private val jdbcTemplate: JdbcTemplate,
    private val apiKeyService: ApiKeyService,
    private val context: ApplicationContext,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    private val devTenantId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Transactional
    override fun run(args: ApplicationArguments) {
        // tenants is FORCE RLS as of V31 (idem_app, which this connection now runs as, is
        // not its owner) -- SET LOCAL app.tenant_id to the tenant's own id, same pattern
        // as every other first-write-for-a-new-tenant path (TenantRepositoryAdapter.create).
        jdbcTemplate.execute("SET LOCAL app.tenant_id = '$devTenantId'")
        jdbcTemplate.update(
            "INSERT INTO tenants (id, created_at, updated_at) VALUES (?, NOW(), NOW()) ON CONFLICT (id) DO NOTHING",
            devTenantId,
        )
        log.info("Dev tenant ready: id=$devTenantId")

        val allScopes = ApiScope.entries.toSet()
        val (rawKey, apiKey) = apiKeyService.generate(TenantId(devTenantId), allScopes)

        println()
        println("=== IDEM DEV BOOTSTRAP ===")
        println("Tenant ID : $devTenantId")
        println("Key ID    : ${apiKey.id.value}")
        println("Scopes    : ${allScopes.joinToString(",")}")
        println()
        println("IDEM_API_KEY=$rawKey")
        println()
        println("Store this key — it will not be shown again.")
        println("==========================")
        println()

        exitProcess(SpringApplication.exit(context, ExitCodeGenerator { 0 }))
    }
}
