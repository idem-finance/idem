package finance.idem.infrastructure.chain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EvmChainReaderFactoryTest {

    @Test
    fun `creates one reader per non-blank rpc url`() {
        val config = EvmChainConfig(
            evm = EvmNetworkConfig("http://eth-rpc"),
            evmBase = EvmNetworkConfig("http://base-rpc"),
            evmPolygon = EvmNetworkConfig(""),
        )

        val readers = EvmChainReaderFactory(config).evmChainReaders()

        assertEquals(2, readers.size)
        assertEquals("EVM_1", readers[0].chainKey)
        assertEquals("EVM_8453", readers[1].chainKey)
    }

    @Test
    fun `creates no readers when all rpc urls are blank`() {
        val readers = EvmChainReaderFactory(EvmChainConfig()).evmChainReaders()

        assertTrue(readers.isEmpty())
    }

    @Test
    fun `creates three readers when all rpc urls are non-blank`() {
        val config = EvmChainConfig(
            evm = EvmNetworkConfig("http://eth-rpc"),
            evmBase = EvmNetworkConfig("http://base-rpc"),
            evmPolygon = EvmNetworkConfig("http://polygon-rpc"),
        )

        val readers = EvmChainReaderFactory(config).evmChainReaders()

        assertEquals(3, readers.size)
        assertEquals("EVM_1", readers[0].chainKey)
        assertEquals("EVM_8453", readers[1].chainKey)
        assertEquals("EVM_137", readers[2].chainKey)
    }
}
