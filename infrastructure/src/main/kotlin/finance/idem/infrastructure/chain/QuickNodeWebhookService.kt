package finance.idem.infrastructure.chain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import finance.idem.application.chain.QuickNodeWebhookUseCase
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.core.chain.ChainCheckpointRepository
import finance.idem.infrastructure.security.HmacSigner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class QuickNodeWebhookService(
    private val watchedAddressRepository: WatchedAddressRepository,
    private val chainCheckpointRepository: ChainCheckpointRepository,
    private val postTransactionUseCase: PostTransactionUseCase,
    private val objectMapper: ObjectMapper,
    private val config: ChainConfig,
    private val deadLetterRecorder: DeadLetterRecorder,
    chainReaders: List<ChainReader>,
) : QuickNodeWebhookUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    // SolanaChainReader is not a standalone bean — extract from the list at construction time.
    // Null when solana.rpc-url is blank (non-Solana deploys, integration tests).
    private val solanaReader: SolanaChainReader? =
        chainReaders.filterIsInstance<SolanaChainReader>().firstOrNull()

    override fun handle(
        signature: String?,
        nonce: String?,
        timestamp: String?,
        rawBody: String,
    ): Result<Unit> {
        val signingKey = config.quicknodeWebhookSecret
        if (signingKey.isNotBlank()) {
            if (signature == null || nonce == null || timestamp == null ||
                !isValidSignature(signingKey, nonce, timestamp, rawBody, signature)
            ) {
                log.warn("QuickNode webhook rejected — invalid or missing X-QN-Signature/X-QN-Nonce/X-QN-Timestamp")
                return Result.failure(IllegalArgumentException("Invalid or missing X-QN-Signature"))
            }
        } else {
            log.warn("QuickNode webhook secret not configured — HMAC validation skipped (dev mode)")
        }

        val streamPayload =
            runCatching {
                objectMapper.readValue<QuickNodeStreamPayload>(rawBody)
            }.getOrElse {
                log.warn("QuickNode webhook: failed to parse payload: ${it.message}")
                return Result.success(Unit)
            }

        for (payload in streamPayload.data) {
            processPayload(payload, streamPayload.metadata.streamId)
        }

        return Result.success(Unit)
    }

    private fun processPayload(
        payload: QuickNodeWebhookPayload,
        streamId: String,
    ) {
        val chainKey =
            networkToChainKey(payload.network) ?: run {
                log.warn("QuickNode webhook: unrecognised network '${payload.network}' (streamId=$streamId) — ignoring")
                return
            }

        val reader =
            solanaReader ?: run {
                log.warn(
                    "QuickNode webhook: no SolanaChainReader available for chainKey=$chainKey — ignoring payload sig=${payload.signature}",
                )
                return
            }

        val existingCheckpoint = chainCheckpointRepository.findByChainKey(chainKey)?.lastBlock ?: 0L
        val watched = watchedAddressRepository.findByChainKey(chainKey)

        if (watched.isNotEmpty()) {
            val tx = reader.getTransaction(payload.signature)
            if (tx != null) {
                for (watchedAddress in watched) {
                    val transfer = reader.decodeTransfer(tx, payload.signature, payload.slot, watchedAddress) ?: continue
                    postTransactionUseCase.execute(transfer.toCommand("quicknode-webhook")).onFailure { error ->
                        deadLetterRecorder.record(transfer, chainKey, "quicknode-webhook", error, logPrefix = "QuickNode webhook")
                    }
                }
            } else {
                log.warn(
                    "QuickNode webhook: getTransaction returned null for sig=${payload.signature} — skipping decode, still advancing checkpoint",
                )
            }
        }

        // Always advance checkpoint regardless of whether any transfer matched — but only
        // once a SolanaChainReader is available to verify the slot (see the reader != null
        // check above; without a reader this method returns early and the checkpoint stays put).
        // Prevents SolanaChainReader from re-scanning already-delivered slots on restart.
        val newCheckpoint = maxOf(existingCheckpoint, payload.slot)
        if (newCheckpoint > existingCheckpoint) {
            runCatching { chainCheckpointRepository.save(chainKey, newCheckpoint) }
                .onFailure {
                    log.error("QuickNode webhook: failed to advance checkpoint for $chainKey to $newCheckpoint", it)
                }
        }
    }

    companion object {
        /**
         * QuickNode signs `nonce + timestamp + rawBody` (in that order, UTF-8) with the
         * stream's security token — NOT the raw body alone. See "How to Validate Incoming
         * Streams Webhook Messages" (QuickNode docs).
         */
        internal fun isValidSignature(
            secret: String,
            nonce: String,
            timestamp: String,
            body: String,
            header: String,
        ): Boolean {
            val expected = HmacSigner.hexHmacSha256(secret, nonce + timestamp + body)
            return MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                header.toByteArray(Charsets.UTF_8),
            )
        }

        internal fun networkToChainKey(network: String): String? =
            when (network.lowercase()) {
                "mainnet-beta" -> "SOLANA"
                "devnet" -> "SOLANA"
                else -> null
            }
    }
}
