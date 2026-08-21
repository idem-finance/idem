package finance.idem.infrastructure.chain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import finance.idem.application.chain.AlchemyWebhookUseCase
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.reconciliation.ReorgReversalCommand
import finance.idem.application.reconciliation.ReorgReversalUseCase
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.chain.ChainCheckpointRepository
import finance.idem.core.monetary.OnChainEntry
import finance.idem.infrastructure.security.HmacSigner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.security.MessageDigest

@Service
class AlchemyWebhookService(
    private val watchedAddressRepository: WatchedAddressRepository,
    private val chainCheckpointRepository: ChainCheckpointRepository,
    private val postTransactionUseCase: PostTransactionUseCase,
    private val objectMapper: ObjectMapper,
    private val config: ChainConfig,
    private val deadLetterRecorder: DeadLetterRecorder,
    private val reorgReversalUseCase: ReorgReversalUseCase,
) : AlchemyWebhookUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(
        signature: String?,
        rawBody: String,
    ): Result<Unit> {
        val signingKey = config.alchemyWebhookSigningKey
        if (signingKey.isNotBlank()) {
            if (signature == null || !isValidSignature(signingKey, rawBody, signature)) {
                log.warn("Alchemy webhook rejected — invalid or missing X-Alchemy-Signature")
                return Result.failure(IllegalArgumentException("Invalid or missing X-Alchemy-Signature"))
            }
        } else {
            log.warn("Alchemy webhook signing key not configured — HMAC validation skipped (dev mode)")
        }

        val payload = objectMapper.readValue<AlchemyWebhookPayload>(rawBody)
        if (payload.type != ADDRESS_ACTIVITY_TYPE) return Result.success(Unit)

        val chainKey =
            networkToChainKey(payload.event.network) ?: run {
                log.warn("Alchemy webhook: unrecognised network '${payload.event.network}' — ignoring")
                return Result.success(Unit)
            }

        val watched = watchedAddressRepository.findByChainKey(chainKey)
        if (watched.isEmpty()) return Result.success(Unit)

        val existingCheckpoint = chainCheckpointRepository.findByChainKey(chainKey)?.lastBlock ?: 0L

        // Advance checkpoint to the max block in the entire payload, regardless of whether any
        // activities matched watched addresses — prevents the fallback EvmChainReader from
        // re-scanning already-delivered blocks on recovery.
        val payloadMaxBlock =
            payload.event.activity
                .mapNotNull { it.blockNum.removePrefix("0x").toLongOrNull(16) }
                .maxOrNull() ?: 0L

        for (activity in payload.event.activity) {
            if (activity.category == "token" && activity.log?.removed == true) {
                handleReorg(activity, chainKey, watched)
                continue
            }
            val transfer = decodeActivity(activity, chainKey, watched) ?: continue
            postTransactionUseCase.execute(transfer.toCommand(FinalityPolicy.WEBHOOK_SOURCE)).onFailure { error ->
                deadLetterRecorder.record(transfer, chainKey, FinalityPolicy.WEBHOOK_SOURCE, error, logPrefix = "Alchemy webhook")
            }
        }

        val newCheckpoint = maxOf(existingCheckpoint, payloadMaxBlock)
        if (newCheckpoint > existingCheckpoint) {
            runCatching { chainCheckpointRepository.save(chainKey, newCheckpoint) }
                .onFailure { log.error("Alchemy webhook: failed to advance checkpoint for $chainKey to $newCheckpoint", it) }
        }

        return Result.success(Unit)
    }

    internal fun decodeActivity(
        activity: AlchemyActivity,
        chainKey: String,
        watched: List<WatchedAddress>,
    ): DetectedTransfer? {
        if (activity.category != "token") return null
        if (activity.log?.removed == true) return null

        val toAddress = activity.toAddress.lowercase()
        val rawContract = activity.rawContract ?: return null
        val contractAddress = rawContract.address?.lowercase() ?: return null

        val watchedAddress =
            watched.firstOrNull {
                it.walletAddress.lowercase() == toAddress &&
                    it.tokenContract.lowercase() == contractAddress
            } ?: return null

        val blockNumber =
            activity.blockNum.removePrefix("0x").toLongOrNull(16) ?: run {
                log.warn("Alchemy webhook: unparseable blockNum '${activity.blockNum}' in tx=${activity.hash}")
                return null
            }

        val logIndex =
            activity.log
                ?.logIndex
                ?.removePrefix("0x")
                ?.toLongOrNull(16)
                ?.toInt() ?: 0

        val rawValueHex =
            rawContract.rawValue?.removePrefix("0x")?.takeIf { it.isNotBlank() } ?: run {
                log.warn("Alchemy webhook: missing rawValue in tx=${activity.hash}")
                return null
            }
        val rawAmount =
            rawValueHex.toBigIntegerOrNull(16) ?: run {
                log.warn("Alchemy webhook: unparseable rawValue '${rawContract.rawValue}' in tx=${activity.hash}")
                return null
            }

        val decimals =
            decimalsFor(watchedAddress.token) ?: run {
                log.error("Alchemy webhook: unsupported token '${watchedAddress.token}' in tx=${activity.hash} — skipping")
                return null
            }

        val amount = MonetaryAmount.of(BigDecimal(rawAmount).movePointLeft(decimals))
        if (!amount.isPositive()) return null

        return DetectedTransfer(
            idempotencyKey = "$chainKey:${activity.hash}:$logIndex",
            entry =
                OnChainEntry(
                    amount = amount,
                    token = watchedAddress.token,
                    chainId = ChainId.EVM,
                    txHash = activity.hash,
                    blockNumber = blockNumber,
                    walletAddress = toAddress,
                    tokenContract = contractAddress,
                    fromAddress = activity.fromAddress.takeIf { it.isNotBlank() }?.lowercase(),
                ),
            watchedAddress = watchedAddress,
            chainKey = chainKey,
            logIndex = logIndex,
        )
    }

    /**
     * A `removed:true` activity means a previously-observed log has been reorged out. If it
     * was ever matched/settled, reverse it via a compensating transaction (see
     * [ReorgReversalService]). If it was never matched (e.g. the original post failed and was
     * dead-lettered, or arrived out of order), [ReorgReversalUseCase] reports
     * `NoMatchingSettlement` and this is a no-op — never throws out of webhook handling.
     */
    private fun handleReorg(
        activity: AlchemyActivity,
        chainKey: String,
        watched: List<WatchedAddress>,
    ) {
        val toAddress = activity.toAddress.lowercase()
        val contractAddress = activity.rawContract?.address?.lowercase() ?: return
        val watchedAddress =
            watched.firstOrNull {
                it.walletAddress.lowercase() == toAddress && it.tokenContract.lowercase() == contractAddress
            } ?: return
        val logIndex =
            activity.log
                ?.logIndex
                ?.removePrefix("0x")
                ?.toLongOrNull(16)
                ?.toInt() ?: 0
        val tenantId = TenantId.of(watchedAddress.tenantId)

        reorgReversalUseCase
            .execute(ReorgReversalCommand(tenantId, activity.hash, logIndex, chainKey, "alchemy webhook: removed=true"))
            .onFailure { error ->
                log.error(
                    "Alchemy webhook: reorg reversal failed txHash={} logIndex={} tenant={}",
                    activity.hash,
                    logIndex,
                    tenantId.value,
                    error,
                )
            }
    }

    companion object {
        private const val ADDRESS_ACTIVITY_TYPE = "ADDRESS_ACTIVITY"

        internal fun isValidSignature(
            signingKey: String,
            rawBody: String,
            signature: String,
        ): Boolean {
            val expected = HmacSigner.hexHmacSha256(signingKey, rawBody)
            return MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                signature.toByteArray(Charsets.UTF_8),
            )
        }

        internal fun networkToChainKey(network: String): String? =
            when (network.uppercase()) {
                "ETH_MAINNET" -> "EVM_1"
                "BASE_MAINNET" -> "EVM_8453"
                "MATIC_MAINNET" -> "EVM_137"
                "ETH_SEPOLIA" -> "EVM_11155111"
                "BASE_SEPOLIA" -> "EVM_84532"
                else -> null
            }

        internal fun decimalsFor(token: StablecoinToken): Int? =
            when (token) {
                StablecoinToken.USDC -> 6
                StablecoinToken.USDT -> 6
                StablecoinToken.PYUSD -> 6
                StablecoinToken.BRZ -> 18
            }
    }
}
