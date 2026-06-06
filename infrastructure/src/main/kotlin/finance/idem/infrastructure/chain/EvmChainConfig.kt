package finance.idem.infrastructure.chain

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("idem.chain")
data class EvmChainConfig(
    val evm: EvmNetworkConfig = EvmNetworkConfig(),
    val evmBase: EvmNetworkConfig = EvmNetworkConfig(),
    val evmPolygon: EvmNetworkConfig = EvmNetworkConfig(),
    val watchedAddresses: List<WatchedAddress> = emptyList(),
)

data class EvmNetworkConfig(
    val rpcUrl: String = "",
)
