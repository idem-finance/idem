package finance.idem.infrastructure.chain

import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.monetary.OnChainEntry
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthLog
import java.math.BigDecimal
import java.math.BigInteger

class EvmChainReader(
    override val chainKey: String,
    private val web3j: Web3j,
    private val watchedAddresses: List<WatchedAddress>,
) : ChainReader {

    override fun poll(checkpoint: Long): List<DetectedTransfer> {
        val contractAddresses = watchedAddresses
            .filter { it.chainKey == chainKey }
            .map { it.tokenContract }
            .distinct()

        if (contractAddresses.isEmpty()) return emptyList()

        val filter = EthFilter(
            DefaultBlockParameter.valueOf(BigInteger.valueOf(checkpoint)),
            DefaultBlockParameter.valueOf("latest"),
            contractAddresses,
        )
        filter.addOptionalTopics(TRANSFER_EVENT_TOPIC)

        return (web3j.ethGetLogs(filter).send().logs ?: emptyList())
            .filterIsInstance<EthLog.LogObject>()
            .mapNotNull { log ->
                decodeTransfer(
                    topics = log.topics,
                    data = log.data,
                    txHash = log.transactionHash,
                    blockNumber = log.blockNumber.toLong(),
                    logIndex = runCatching { log.logIndex.toInt() }.getOrDefault(0),
                    contractAddress = log.address,
                )
            }
    }

    internal fun decodeTransfer(
        topics: List<String>,
        data: String,
        txHash: String,
        blockNumber: Long,
        logIndex: Int,
        contractAddress: String,
    ): DetectedTransfer? {
        if (topics.size < 3) return null
        if (topics[0] != TRANSFER_EVENT_TOPIC) return null

        val toAddress = "0x" + topics[2].takeLast(40).lowercase()

        val watched = watchedAddresses.firstOrNull { wa ->
            wa.chainKey == chainKey &&
                wa.tokenContract.lowercase() == contractAddress.lowercase() &&
                wa.walletAddress.lowercase() == toAddress
        } ?: return null

        val rawAmount = BigInteger(data.removePrefix("0x"), 16)
        val decimals = decimalsFor(watched.token)
        val amount = MonetaryAmount.of(BigDecimal(rawAmount).movePointLeft(decimals))

        return DetectedTransfer(
            idempotencyKey = "$chainKey:$txHash:$logIndex",
            entry = OnChainEntry(
                amount = amount,
                token = watched.token,
                chainId = ChainId.EVM,
                txHash = txHash,
                blockNumber = blockNumber,
                walletAddress = toAddress,
                tokenContract = contractAddress,
            ),
        )
    }

    companion object {
        const val TRANSFER_EVENT_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

        private fun decimalsFor(token: StablecoinToken): Int = when (token) {
            StablecoinToken.USDC -> 6
            StablecoinToken.USDT -> 6
            StablecoinToken.PYUSD -> 6
            StablecoinToken.BRZ -> 18
        }
    }
}
