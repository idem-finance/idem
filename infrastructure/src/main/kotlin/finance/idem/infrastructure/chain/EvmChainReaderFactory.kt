package finance.idem.infrastructure.chain

import jakarta.annotation.PreDestroy
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService

@Configuration
@EnableConfigurationProperties(EvmChainConfig::class)
class EvmChainReaderFactory(private val config: EvmChainConfig) {

    private val web3jInstances = mutableListOf<Web3j>()

    @Bean
    fun evmChainReaders(): List<ChainReader> = buildList {
        if (config.evm.rpcUrl.isNotBlank()) {
            add(EvmChainReader("EVM_1", buildWeb3j(config.evm.rpcUrl), config.watchedAddresses))
        }
        if (config.evmBase.rpcUrl.isNotBlank()) {
            add(EvmChainReader("EVM_8453", buildWeb3j(config.evmBase.rpcUrl), config.watchedAddresses))
        }
        if (config.evmPolygon.rpcUrl.isNotBlank()) {
            add(EvmChainReader("EVM_137", buildWeb3j(config.evmPolygon.rpcUrl), config.watchedAddresses))
        }
    }

    @PreDestroy
    fun shutdown() {
        web3jInstances.forEach { it.shutdown() }
    }

    private fun buildWeb3j(rpcUrl: String): Web3j =
        Web3j.build(HttpService(rpcUrl)).also { web3jInstances += it }
}
