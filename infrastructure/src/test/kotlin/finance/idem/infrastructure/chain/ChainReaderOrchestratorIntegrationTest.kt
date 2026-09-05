package finance.idem.infrastructure.chain

import finance.idem.application.port.IdempotencyStore
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.chain.ChainCheckpointRepository
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.monetary.OnChainEntry
import finance.idem.infrastructure.persistence.chain.FailedChainTransferJpaRepository
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Import(ChainReaderOrchestratorIntegrationTest.FakeChainReadersConfig::class)
class ChainReaderOrchestratorIntegrationTest {
    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16")
                .withDatabaseName("idem_test")
                .withUsername("idem")
                .withPassword("idem")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("idem.chain.tron.polling-interval-ms") { "200" }
            registry.add("idem.scheduling.distributed-lock.enabled") { "true" }
        }
    }

    @TestConfiguration
    @EnableScheduling
    class FakeChainReadersConfig(
        private val accountRepository: AccountRepository,
    ) {
        val tenantId: TenantId = TenantId.generate()
        val debitAccountId: AccountId = AccountId.generate()
        val creditAccountId: AccountId = AccountId.generate()

        val evmTransfer = transfer(chainKey = "EVM_1", txHash = "0xevm1", blockNumber = 100L)
        val tronTransfer = transfer(chainKey = "TRON", txHash = "tronTx1", blockNumber = 200L)

        // debitAccountId is never seeded, so PostTransactionUseCase.execute() fails with
        // TransactionAccountNotFound — exercises the dead-letter path on chain-recovery.
        val deadLetterTransfer =
            transfer(
                chainKey = "EVM_8453",
                txHash = "0xdead",
                blockNumber = 300L,
                debitAccountId = AccountId.generate(),
            )

        private fun transfer(
            chainKey: String,
            txHash: String,
            blockNumber: Long,
            debitAccountId: AccountId = this.debitAccountId,
            creditAccountId: AccountId = this.creditAccountId,
        ) = DetectedTransfer(
            idempotencyKey = "$chainKey:$txHash",
            entry =
                OnChainEntry(
                    amount = MonetaryAmount.of("100.000000"),
                    token = StablecoinToken.USDC,
                    chainId = ChainId.EVM,
                    txHash = txHash,
                    blockNumber = blockNumber,
                    walletAddress = "0xwatched",
                    tokenContract = "0xcontract",
                ),
            watchedAddress =
                WatchedAddress(
                    chainKey = chainKey,
                    walletAddress = "0xwatched",
                    tokenContract = "0xcontract",
                    token = StablecoinToken.USDC,
                    tenantId = tenantId.value.toString(),
                    debitAccountId = debitAccountId.value.toString(),
                    creditAccountId = creditAccountId.value.toString(),
                ),
        )

        @PostConstruct
        fun seedAccounts() {
            val now = Instant.now()
            accountRepository.save(
                Account.create(
                    debitAccountId,
                    tenantId,
                    "On-chain wallet",
                    FiatCurrency.USD,
                    AccountType.ASSET,
                    now,
                    "test",
                ),
            )
            accountRepository.save(
                Account.create(
                    creditAccountId,
                    tenantId,
                    "Settlement clearing",
                    FiatCurrency.USD,
                    AccountType.LIABILITY,
                    now,
                    "test",
                ),
            )
        }

        @Bean
        fun fakeEvmReader(): ChainReader =
            mock<ChainReader>().also {
                whenever(it.chainKey).thenReturn("EVM_1")
                whenever(it.poll(any())).thenReturn(listOf(evmTransfer))
            }

        @Bean
        fun fakeTronReader(): ChainReader =
            mock<ChainReader>().also {
                whenever(it.chainKey).thenReturn("TRON")
                whenever(it.poll(any())).thenReturn(listOf(tronTransfer))
            }

        @Bean
        fun fakeDeadLetterReader(): ChainReader =
            mock<ChainReader>().also {
                whenever(it.chainKey).thenReturn("EVM_8453")
                whenever(it.poll(any())).thenReturn(listOf(deadLetterTransfer))
            }

        @Bean
        @Primary
        fun fakeChainReaderList(
            fakeEvmReader: ChainReader,
            fakeTronReader: ChainReader,
            fakeDeadLetterReader: ChainReader,
        ): List<ChainReader> = listOf(fakeEvmReader, fakeTronReader, fakeDeadLetterReader)
    }

    @Autowired
    @Qualifier("fakeEvmReader")
    lateinit var fakeEvmReader: ChainReader

    @Autowired
    @Qualifier("fakeTronReader")
    lateinit var fakeTronReader: ChainReader

    @Autowired lateinit var fakeConfig: FakeChainReadersConfig

    @Autowired lateinit var chainCheckpointRepository: ChainCheckpointRepository

    @Autowired lateinit var transactionRepository: TransactionRepository

    @Autowired lateinit var idempotencyStore: IdempotencyStore

    @Autowired lateinit var meterRegistry: MeterRegistry

    @Autowired lateinit var failedChainTransferJpaRepository: FailedChainTransferJpaRepository

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `startup recovery polls the EVM reader once and posts the transfer`() {
        // onApplicationStarted() dispatches to chainRecoveryExecutor and returns immediately,
        // so the sweep may still be running on its background virtual thread here.
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            verify(fakeEvmReader, times(1)).poll(0L)

            val checkpoint = chainCheckpointRepository.findByChainKey("EVM_1")
            assertNotNull(checkpoint)
            assertEquals(100L, checkpoint.lastBlock)

            val txId = idempotencyStore.find(fakeConfig.evmTransfer.idempotencyKey, fakeConfig.tenantId)
            assertNotNull(txId)
            val tx = transactionRepository.findById(txId, fakeConfig.tenantId)
            assertNotNull(tx)
            assertEquals(2, tx.lines.size)
            assertEquals("chain-recovery", tx.createdBy)

            val lockRow =
                jdbcTemplate.queryForMap(
                    "SELECT locked_at, lock_until FROM shedlock WHERE name = ?",
                    ChainReaderOrchestrator.RECOVERY_SWEEP_LOCK_NAME,
                )
            assertNotNull(lockRow["locked_at"])
            assertNotNull(lockRow["lock_until"])
        }
    }

    @Test
    fun `startup recovery dead-letters a transfer whose account does not exist`() {
        // onApplicationStarted() dispatches to chainRecoveryExecutor and returns immediately,
        // so the sweep may still be running on its background virtual thread here.
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val checkpoint = chainCheckpointRepository.findByChainKey("EVM_8453")
            assertNotNull(checkpoint)
            assertEquals(fakeConfig.deadLetterTransfer.entry.blockNumber, checkpoint.lastBlock)

            val counter =
                meterRegistry
                    .get(ChainMetrics.DEAD_LETTER_COUNTER)
                    .tag(ChainMetrics.TAG_CHAIN_KEY, "EVM_8453")
                    .tag(ChainMetrics.TAG_SOURCE, "chain-recovery")
                    .counter()
            assertEquals(1.0, counter.count())

            val row =
                failedChainTransferJpaRepository
                    .findAll()
                    .find { it.idempotencyKey == fakeConfig.deadLetterTransfer.idempotencyKey }
            assertNotNull(row)
            assertEquals("EVM_8453", row.chainKey)
            assertEquals("chain-recovery", row.source)
            assertTrue(row.errorMessage.startsWith("Account not found"))
        }
    }

    @Test
    fun `Tron poller fires repeatedly and posts the transfer`() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            verify(fakeTronReader, atLeast(2)).poll(any())
        }

        val checkpoint = chainCheckpointRepository.findByChainKey("TRON")
        assertNotNull(checkpoint)
        assertEquals(200L, checkpoint.lastBlock)

        val txId = idempotencyStore.find(fakeConfig.tronTransfer.idempotencyKey, fakeConfig.tenantId)
        assertNotNull(txId)
        val tx = transactionRepository.findById(txId, fakeConfig.tenantId)
        assertNotNull(tx)
        assertEquals(2, tx.lines.size)
        assertEquals("tron-poller", tx.createdBy)

        val lockRow = jdbcTemplate.queryForMap("SELECT locked_at, lock_until FROM shedlock WHERE name = ?", "pollTron")
        assertNotNull(lockRow["locked_at"])
        assertNotNull(lockRow["lock_until"])
    }
}
