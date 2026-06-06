package finance.idem.infrastructure.chain

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService

@Configuration
@EnableConfigurationProperties(EvmChainConfig::class)
class EvmChainReaderFactory(private val config: EvmChainConfig) {

    @Bean
    fun evmChainReaders(): List<ChainReader> = buildList {
        if (config.evm.rpcUrl.isNotBlank()) {
            add(EvmChainReader("EVM_1", Web3j.build(HttpService(config.evm.rpcUrl)), config.watchedAddresses))
        }
        if (config.evmBase.rpcUrl.isNotBlank()) {
            add(EvmChainReader("EVM_8453", Web3j.build(HttpService(config.evmBase.rpcUrl)), config.watchedAddresses))
        }
        if (config.evmPolygon.rpcUrl.isNotBlank()) {
            add(EvmChainReader("EVM_137", Web3j.build(HttpService(config.evmPolygon.rpcUrl)), config.watchedAddresses))
        }
    }
}
