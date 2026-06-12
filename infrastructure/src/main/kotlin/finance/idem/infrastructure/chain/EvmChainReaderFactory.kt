package finance.idem.infrastructure.chain

import jakarta.annotation.PreDestroy
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import java.io.Closeable

@Configuration
@EnableConfigurationProperties(ChainConfig::class)
class EvmChainReaderFactory(
    private val config: ChainConfig,
    private val watchedAddressRepository: WatchedAddressRepository,
) {

    private val web3jInstances = mutableListOf<Web3j>()
    private var readers: List<ChainReader> = emptyList()

    @Bean
    fun chainReaders(): List<ChainReader> {
        readers = buildList {
            if (config.evm.rpcUrl.isNotBlank()) {
                add(EvmChainReader("EVM_1", buildWeb3j(config.evm.rpcUrl), watchedAddressRepository))
            }
            if (config.evmBase.rpcUrl.isNotBlank()) {
                add(EvmChainReader("EVM_8453", buildWeb3j(config.evmBase.rpcUrl), watchedAddressRepository))
            }
            if (config.evmPolygon.rpcUrl.isNotBlank()) {
                add(EvmChainReader("EVM_137", buildWeb3j(config.evmPolygon.rpcUrl), watchedAddressRepository))
            }
            if (config.solana.rpcUrl.isNotBlank()) {
                add(SolanaChainReader(config.solana.rpcUrl, watchedAddressRepository))
            }
            if (config.tron.apiUrl.isNotBlank()) {
                add(TronChainReader(config.tron.apiUrl, watchedAddressRepository, apiKey = config.tron.apiKey))
            }
        }
        return readers
    }

    @PreDestroy
    fun shutdown() {
        web3jInstances.forEach { it.shutdown() }
        readers.filterIsInstance<Closeable>().forEach(Closeable::close)
    }

    private fun buildWeb3j(rpcUrl: String): Web3j =
        Web3j.build(HttpService(rpcUrl)).also { web3jInstances += it }
}
