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
    // Prefer the RPC's actual post-merge `finalized` block tag (a real consensus-finality
    // guarantee) over a fixed block-count heuristic. `confirmations` is used only as a
    // fallback when the endpoint doesn't support the tag — a probabilistic bet, not a
    // guarantee, especially on chains without a fast-finality mechanism.
    val useFinalizedTag: Boolean = true,
    val confirmations: Long = 12,
)

data class SolanaNetworkConfig(
    val rpcUrl: String = "",
    val batchSize: Int = 100,
)

data class TronNetworkConfig(
    val apiUrl: String = "",
    val apiKey: String = "",
)
