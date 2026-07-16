package finance.idem.infrastructure.persistence.chain

import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ChainCheckpointRepositoryAdapter::class)
class ChainCheckpointRepositoryAdapterTest : SharedPostgresTestBase() {
    @Autowired lateinit var adapter: ChainCheckpointRepositoryAdapter

    @Test
    fun `save and findByChainKey round-trip preserves chainKey and lastBlock`() {
        adapter.save("EVM_1", 19_000_000L)

        val found = adapter.findByChainKey("EVM_1")

        assertNotNull(found)
        assertEquals("EVM_1", found.chainKey)
        assertEquals(19_000_000L, found.lastBlock)
        assertNotNull(found.updatedAt)
    }

    @Test
    fun `findByChainKey returns null for unknown chainKey`() {
        val result = adapter.findByChainKey("UNKNOWN_CHAIN")
        assertNull(result)
    }

    @Test
    fun `save upserts — second save overwrites lastBlock without inserting duplicate`() {
        adapter.save("SOLANA", 250_000_000L)
        adapter.save("SOLANA", 250_001_000L)

        val found = adapter.findByChainKey("SOLANA")

        assertNotNull(found)
        assertEquals(250_001_000L, found.lastBlock)
    }

    @Test
    fun `independent chainKeys do not interfere with each other`() {
        adapter.save("EVM_1", 100L)
        adapter.save("EVM_8453", 200L)
        adapter.save("TRON", 300L)

        assertEquals(100L, adapter.findByChainKey("EVM_1")?.lastBlock)
        assertEquals(200L, adapter.findByChainKey("EVM_8453")?.lastBlock)
        assertEquals(300L, adapter.findByChainKey("TRON")?.lastBlock)
    }
}
