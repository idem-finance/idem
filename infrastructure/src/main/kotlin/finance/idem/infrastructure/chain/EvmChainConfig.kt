package finance.idem.infrastructure.chain

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("idem.chain")
data class ChainConfig(
    val evm: EvmNetworkConfig = EvmNetworkConfig(),
    val evmBase: EvmNetworkConfig = EvmNetworkConfig(),
    val evmPolygon: EvmNetworkConfig = EvmNetworkConfig(),
    val solana: SolanaNetworkConfig = SolanaNetworkConfig(),
    val alchemyWebhookSigningKey: String = "",
    val quicknodeWebhookSecret: String = "",
    val tron: TronNetworkConfig = TronNetworkConfig(),
)

data class EvmNetworkConfig(
    val rpcUrl: String = "",
)

data class SolanaNetworkConfig(
    val rpcUrl: String = "",
)

data class TronNetworkConfig(
    val apiUrl: String = "",
    val apiKey: String = "",
)
