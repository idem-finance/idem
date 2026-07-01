package finance.idem.infrastructure.chain

import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.monetary.OnChainEntry
import org.slf4j.LoggerFactory
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthLog
import java.math.BigDecimal
import java.math.BigInteger

class EvmChainReader(
    override val chainKey: String,
    private val web3j: Web3j,
    private val watchedAddressRepository: WatchedAddressRepository,
    private val maxBlockRange: Long = MAX_BLOCK_RANGE,
) : ChainReader {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun poll(checkpoint: Long): List<DetectedTransfer> {
        val relevant = watchedAddressRepository.findByChainKey(chainKey)
        if (relevant.isEmpty()) return emptyList()

        val contractAddresses = relevant.map { it.tokenContract }.distinct()
        val latestBlock =
            web3j
                .ethBlockNumber()
                .send()
                .blockNumber
                .toLong()

        return generateSequence(checkpoint) { from ->
            val next = from + maxBlockRange
            if (next <= latestBlock) next else null
        }.map { from ->
            val to = minOf(from + maxBlockRange - 1, latestBlock)
            pollRange(from, to, contractAddresses, relevant)
        }.flatten()
            .toList()
    }

    private fun pollRange(
        from: Long,
        to: Long,
        contractAddresses: List<String>,
        relevant: List<WatchedAddress>,
    ): List<DetectedTransfer> {
        val toAddresses = relevant.map { paddedAddress(it.walletAddress) }.distinct()

        val filter =
            EthFilter(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(from)),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(to)),
                contractAddresses,
            )
        // topic[0] = Transfer signature; topic[1] = from (any); topic[2] = to (watched)
        filter.addSingleTopic(TRANSFER_EVENT_TOPIC)
        filter.addSingleTopic(null)
        filter.addOptionalTopics(*toAddresses.toTypedArray())

        return (web3j.ethGetLogs(filter).send().logs ?: emptyList())
            .filterIsInstance<EthLog.LogObject>()
            .mapNotNull { log ->
                val logIndex = log.logIndex?.toIntOrNull()
                if (logIndex == null) {
                    log.warn("skipping log with unparseable logIndex in tx=${log.transactionHash}")
                    return@mapNotNull null
                }
                decodeTransfer(
                    topics = log.topics,
                    data = log.data,
                    txHash = log.transactionHash,
                    blockNumber = log.blockNumber.toLong(),
                    logIndex = logIndex,
                    contractAddress = log.address,
                    relevant = relevant,
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
        relevant: List<WatchedAddress> = watchedAddressRepository.findByChainKey(chainKey),
    ): DetectedTransfer? {
        if (topics.size < 3) return null
        if (topics[0] != TRANSFER_EVENT_TOPIC) return null

        val toAddress = "0x" + topics[2].takeLast(40).lowercase()
        val fromAddress = "0x" + topics[1].takeLast(40).lowercase()
        val normalizedContract = contractAddress.lowercase()

        val watched =
            relevant.firstOrNull { wa ->
                wa.tokenContract.lowercase() == normalizedContract &&
                    wa.walletAddress.lowercase() == toAddress
            } ?: return null

        val rawAmount = BigInteger(data.removePrefix("0x"), 16)
        val decimals = decimalsFor(watched.token)
        val amount = MonetaryAmount.of(BigDecimal(rawAmount).movePointLeft(decimals))

        return DetectedTransfer(
            idempotencyKey = "$chainKey:$txHash:$logIndex",
            entry =
                OnChainEntry(
                    amount = amount,
                    token = watched.token,
                    chainId = ChainId.EVM,
                    txHash = txHash,
                    blockNumber = blockNumber,
                    walletAddress = toAddress,
                    tokenContract = normalizedContract,
                    fromAddress = fromAddress,
                ),
            watchedAddress = watched,
        )
    }

    private fun BigInteger?.toIntOrNull(): Int? = runCatching { this?.intValueExact() }.getOrNull()

    private fun Any.warn(msg: String) = log.warn(msg)

    companion object {
        const val TRANSFER_EVENT_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
        private const val MAX_BLOCK_RANGE = 2_000L

        // Pads a 20-byte address to 32 bytes for topic matching (topic[2]).
        internal fun paddedAddress(address: String): String = "0x000000000000000000000000${address.removePrefix("0x").lowercase()}"

        private fun decimalsFor(token: StablecoinToken): Int =
            when (token) {
                StablecoinToken.USDC -> 6
                StablecoinToken.USDT -> 6
                StablecoinToken.PYUSD -> 6
                StablecoinToken.BRZ -> 18
            }
    }
}
