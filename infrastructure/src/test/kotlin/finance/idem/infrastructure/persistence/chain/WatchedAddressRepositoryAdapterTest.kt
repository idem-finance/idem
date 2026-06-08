package finance.idem.infrastructure.persistence.chain

import finance.idem.core.StablecoinToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(WatchedAddressRepositoryAdapter::class)
class WatchedAddressRepositoryAdapterTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16")
            .withDatabaseName("idem_test")
            .withUsername("idem")
            .withPassword("idem")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var jpaRepository: WatchedAddressJpaRepository
    @Autowired lateinit var adapter: WatchedAddressRepositoryAdapter
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val tenantId = UUID.fromString("a1b2c3d4-0000-0000-0000-000000000001")
    private val debitId  = UUID.fromString("a1b2c3d4-0000-0000-0000-000000000002")
    private val creditId = UUID.fromString("a1b2c3d4-0000-0000-0000-000000000003")

    @BeforeEach
    fun insertAccounts() {
        // accounts has FORCE RLS — app.tenant_id must be set before any DML
        jdbcTemplate.execute("SET LOCAL app.tenant_id = '$tenantId'")
        jdbcTemplate.update(
            "INSERT INTO accounts (id, tenant_id, name, currency, type, created_by) VALUES (?::UUID, ?::UUID, ?, ?, ?, ?)",
            debitId.toString(), tenantId.toString(), "Debit Account", "USD", "ASSET", "test",
        )
        jdbcTemplate.update(
            "INSERT INTO accounts (id, tenant_id, name, currency, type, created_by) VALUES (?::UUID, ?::UUID, ?, ?, ?, ?)",
            creditId.toString(), tenantId.toString(), "Credit Account", "USD", "LIABILITY", "test",
        )
    }

    @Test
    fun `findByChainKey returns matching watched addresses`() {
        jpaRepository.save(watchedAddressRow("EVM_1", "0xwallet1", "0xusdc", "USDC"))

        val result = adapter.findByChainKey("EVM_1")

        assertEquals(1, result.size)
        assertEquals("EVM_1", result[0].chainKey)
        assertEquals("0xwallet1", result[0].walletAddress)
        assertEquals("0xusdc", result[0].tokenContract)
        assertEquals(StablecoinToken.USDC, result[0].token)
        assertEquals(tenantId.toString(), result[0].tenantId)
        assertEquals(debitId.toString(), result[0].debitAccountId)
        assertEquals(creditId.toString(), result[0].creditAccountId)
    }

    @Test
    fun `findByChainKey returns empty list for unknown chain key`() {
        val result = adapter.findByChainKey("SOLANA")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findByChainKey returns all addresses for a given chain key`() {
        jpaRepository.save(watchedAddressRow("EVM_1", "0xwallet1", "0xusdc", "USDC"))
        jpaRepository.save(watchedAddressRow("EVM_1", "0xwallet2", "0xusdt", "USDT"))
        jpaRepository.save(watchedAddressRow("EVM_8453", "0xwallet3", "0xusdc_base", "USDC"))

        val result = adapter.findByChainKey("EVM_1")

        assertEquals(2, result.size)
        assertTrue(result.all { it.chainKey == "EVM_1" })
    }

    @Test
    fun `findByChainKey isolates chain keys — different chain returns different results`() {
        jpaRepository.save(watchedAddressRow("EVM_1", "0xwallet1", "0xusdc", "USDC"))
        jpaRepository.save(watchedAddressRow("EVM_137", "0xwallet2", "0xusdc_polygon", "USDC"))

        assertEquals(1, adapter.findByChainKey("EVM_1").size)
        assertEquals(1, adapter.findByChainKey("EVM_137").size)
        assertTrue(adapter.findByChainKey("EVM_8453").isEmpty())
    }

    private fun watchedAddressRow(
        chainKey: String,
        walletAddress: String,
        tokenContract: String,
        token: String,
    ) = WatchedAddressDataModel(
        id = UUID.randomUUID(),
        chainKey = chainKey,
        walletAddress = walletAddress,
        tokenContract = tokenContract,
        token = token,
        tenantId = tenantId,
        debitAccountId = debitId,
        creditAccountId = creditId,
        createdAt = Instant.now(),
    )
}
