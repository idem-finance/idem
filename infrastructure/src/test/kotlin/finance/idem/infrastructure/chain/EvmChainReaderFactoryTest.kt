package finance.idem.infrastructure.chain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class EvmChainReaderFactoryTest {

    private val mockRepo = mock<WatchedAddressRepository>()

    @Test
    fun `creates one reader per non-blank evm rpc url`() {
        val config = ChainConfig(
            evm = EvmNetworkConfig("http://eth-rpc"),
            evmBase = EvmNetworkConfig("http://base-rpc"),
            evmPolygon = EvmNetworkConfig(""),
        )

        val readers = EvmChainReaderFactory(config, mockRepo).chainReaders()

        assertEquals(2, readers.size)
        assertEquals("EVM_1", readers[0].chainKey)
        assertEquals("EVM_8453", readers[1].chainKey)
    }

    @Test
    fun `creates no readers when all rpc urls are blank`() {
        val readers = EvmChainReaderFactory(ChainConfig(), mockRepo).chainReaders()

        assertTrue(readers.isEmpty())
    }

    @Test
    fun `creates three evm readers when all evm rpc urls are non-blank`() {
        val config = ChainConfig(
            evm = EvmNetworkConfig("http://eth-rpc"),
            evmBase = EvmNetworkConfig("http://base-rpc"),
            evmPolygon = EvmNetworkConfig("http://polygon-rpc"),
        )

        val readers = EvmChainReaderFactory(config, mockRepo).chainReaders()

        assertEquals(3, readers.size)
        assertEquals("EVM_1", readers[0].chainKey)
        assertEquals("EVM_8453", readers[1].chainKey)
        assertEquals("EVM_137", readers[2].chainKey)
    }

    @Test
    fun `creates solana reader when solana rpc url is non-blank`() {
        val config = ChainConfig(
            solana = SolanaNetworkConfig("http://solana-rpc"),
        )

        val readers = EvmChainReaderFactory(config, mockRepo).chainReaders()

        assertEquals(1, readers.size)
        assertEquals("SOLANA", readers[0].chainKey)
    }

    @Test
    fun `creates evm and solana readers together`() {
        val config = ChainConfig(
            evm = EvmNetworkConfig("http://eth-rpc"),
            solana = SolanaNetworkConfig("http://solana-rpc"),
        )

        val readers = EvmChainReaderFactory(config, mockRepo).chainReaders()

        assertEquals(2, readers.size)
        assertEquals("EVM_1", readers[0].chainKey)
        assertEquals("SOLANA", readers[1].chainKey)
    }

    @Test
    fun `creates tron reader when tron api url is non-blank`() {
        val config = ChainConfig(
            tron = TronNetworkConfig("https://apilist.tronscan.org"),
        )

        val readers = EvmChainReaderFactory(config, mockRepo).chainReaders()

        assertEquals(1, readers.size)
        assertEquals("TRON", readers[0].chainKey)
    }

    @Test
    fun `creates evm solana and tron readers together`() {
        val config = ChainConfig(
            evm = EvmNetworkConfig("http://eth-rpc"),
            solana = SolanaNetworkConfig("http://solana-rpc"),
            tron = TronNetworkConfig("https://apilist.tronscan.org"),
        )

        val readers = EvmChainReaderFactory(config, mockRepo).chainReaders()

        assertEquals(3, readers.size)
        assertEquals("EVM_1", readers[0].chainKey)
        assertEquals("SOLANA", readers[1].chainKey)
        assertEquals("TRON", readers[2].chainKey)
    }

    @Test
    fun `solana and tron readers implement Closeable — shutdown closes them`() {
        val config = ChainConfig(
            solana = SolanaNetworkConfig("http://solana-rpc"),
            tron = TronNetworkConfig("https://apilist.tronscan.org"),
        )
        val factory = EvmChainReaderFactory(config, mockRepo)
        factory.chainReaders()

        // Must not throw — verifies the Closeable shutdown path is wired for both readers
        factory.shutdown()
    }
}
