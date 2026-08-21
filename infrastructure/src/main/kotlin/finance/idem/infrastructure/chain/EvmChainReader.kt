package finance.idem.infrastructure.chain

import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.monetary.OnChainEntry
import org.slf4j.LoggerFactory
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthLog
import java.math.BigDecimal
import java.math.BigInteger

class EvmChainReader(
    override val chainKey: String,
    private val web3j: Web3j,
    private val watchedAddressRepository: WatchedAddressRepository,
    private val useFinalizedTag: Boolean = true,
    private val confirmations: Long = 12L,
    private val maxBlockRange: Long = MAX_BLOCK_RANGE,
) : ChainReader {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun poll(checkpoint: Long): List<DetectedTransfer> {
        val relevant = watchedAddressRepository.findByChainKey(chainKey)
        if (relevant.isEmpty()) return emptyList()

        val contractAddresses = relevant.map { it.tokenContract }.distinct()
        val latestBlock = resolveScanBound().blockNumber
        if (latestBlock <= checkpoint) return emptyList()

        return generateSequence(checkpoint) { from ->
            val next = from + maxBlockRange
            if (next <= latestBlock) next else null
        }.map { from ->
            val to = minOf(from + maxBlockRange - 1, latestBlock)
            pollRange(from, to, contractAddresses, relevant)
        }.flatten()
            .toList()
    }

    /**
     * Prefers the RPC's actual post-merge `finalized` block tag — a real consensus-finality
     * guarantee ("this block will not be reverted short of an ~impossible attack"), not a
     * probabilistic block-depth heuristic. Falls back to `latestBlock - confirmations` only
     * when the endpoint doesn't support the tag (older/self-hosted nodes, some testnets) —
     * that fallback is explicitly weaker and must never be presented as equivalent. The
     * mechanism used is reported in [EvmScanBound.source] so callers (`BasicReconciliationService`
     * via `SettlementFinalityPoller`) can record which guarantee level applies to each entry.
     */
    internal fun resolveScanBound(): EvmScanBound {
        if (useFinalizedTag) {
            val finalized = runCatching { web3j.ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false).send() }.getOrNull()
            val finalizedBlockNumber =
                finalized
                    ?.takeUnless { it.hasError() }
                    ?.block
                    ?.number
                    ?.toLong()
            if (finalizedBlockNumber != null) {
                return EvmScanBound(finalizedBlockNumber, ConfirmationSource.FINALIZED_TAG, confirmationsUsed = null)
            }
            log.warn(
                "$chainKey: eth_getBlockByNumber('finalized') unsupported or failed" +
                    (finalized?.error?.message?.let { " ($it)" } ?: "") +
                    " -- falling back to a $confirmations-block depth heuristic (probabilistic, not a finality guarantee)",
            )
        }
        val rawTip =
            web3j
                .ethBlockNumber()
                .send()
                .blockNumber
                .toLong()
        val bound = (rawTip - confirmations).coerceAtLeast(0L)
        return EvmScanBound(bound, ConfirmationSource.BLOCK_DEPTH_HEURISTIC, confirmationsUsed = confirmations)
    }

    /**
     * Whether `txHash`'s log at `logIndex` is still present on-chain, in the exact block it
     * was originally observed in — the active-verification check `SettlementFinalityPoller`
     * runs before promoting a WATCHING entry to SETTLED, since a passive `removed:true`
     * webhook delivery can be missed (network blip, provider outage). A reorg shows up as
     * [LogVerification.Absent] — either a missing receipt entirely, or a receipt whose block
     * number has moved (the transaction was re-mined in a later block). An RPC failure (timeout,
     * error response) is reported separately as [LogVerification.VerificationFailed] rather than
     * being conflated with [LogVerification.Absent] — infrastructure flakiness must never be
     * treated as proof a log is gone.
     */
    fun verifyLogStillPresent(
        txHash: String,
        logIndex: Int,
        expectedBlockNumber: Long,
    ): LogVerification {
        val result = runCatching { web3j.ethGetTransactionReceipt(txHash).send() }
        val response = result.getOrElse { return LogVerification.VerificationFailed(it) }
        if (response.hasError()) {
            return LogVerification.VerificationFailed(RuntimeException(response.error?.message ?: "eth_getTransactionReceipt error"))
        }
        val receipt = response.transactionReceipt.orElse(null) ?: return LogVerification.Absent
        if (receipt.blockNumber.toLong() != expectedBlockNumber) return LogVerification.Absent
        return if (receipt.logs.any { it.logIndex.toLong() == logIndex.toLong() }) {
            LogVerification.Present
        } else {
            LogVerification.Absent
        }
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
            idempotencyKey = ChainIdempotencyKey.of(chainKey, txHash, logIndex),
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
            chainKey = chainKey,
            logIndex = logIndex,
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

internal data class EvmScanBound(
    val blockNumber: Long,
    val source: String,
    val confirmationsUsed: Long?,
)

internal object ConfirmationSource {
    const val FINALIZED_TAG = "finalized_tag"
    const val BLOCK_DEPTH_HEURISTIC = "block_depth_heuristic"
}

/** Outcome of [EvmChainReader.verifyLogStillPresent] — see its doc comment for why this is a
 * 3-state result rather than a Boolean. */
sealed class LogVerification {
    object Present : LogVerification()

    object Absent : LogVerification()

    data class VerificationFailed(
        val cause: Throwable,
    ) : LogVerification()
}
