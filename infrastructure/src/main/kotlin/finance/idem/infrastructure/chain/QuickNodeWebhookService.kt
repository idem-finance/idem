package finance.idem.infrastructure.chain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import finance.idem.application.chain.QuickNodeWebhookPort
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.core.chain.ChainCheckpointRepository
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.TenantId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class QuickNodeWebhookService(
    private val watchedAddressRepository: WatchedAddressRepository,
    private val chainCheckpointRepository: ChainCheckpointRepository,
    private val postTransactionUseCase: PostTransactionUseCase,
    private val objectMapper: ObjectMapper,
    private val config: ChainConfig,
    chainReaders: List<ChainReader>,
) : QuickNodeWebhookPort {

    private val log = LoggerFactory.getLogger(javaClass)

    // SolanaChainReader is not a standalone bean — extract from the list at construction time.
    // Null when solana.rpc-url is blank (non-Solana deploys, integration tests).
    private val solanaReader: SolanaChainReader? =
        chainReaders.filterIsInstance<SolanaChainReader>().firstOrNull()

    override fun handle(signature: String?, rawBody: String): Result<Unit> {
        val signingKey = config.quicknodeWebhookSecret
        if (signingKey.isNotBlank()) {
            if (signature == null || !isValidSignature(signingKey, rawBody, signature)) {
                log.warn("QuickNode webhook rejected — invalid or missing X-QN-Signature")
                return Result.failure(IllegalArgumentException("Invalid or missing X-QN-Signature"))
            }
        } else {
            log.warn("QuickNode webhook secret not configured — HMAC validation skipped (dev mode)")
        }

        val payloads = runCatching {
            objectMapper.readValue<List<QuickNodeWebhookPayload>>(rawBody)
        }.getOrElse {
            log.warn("QuickNode webhook: failed to parse payload: ${it.message}")
            return Result.success(Unit)
        }

        for (payload in payloads) {
            processPayload(payload)
        }

        return Result.success(Unit)
    }

    private fun processPayload(payload: QuickNodeWebhookPayload) {
        val chainKey = networkToChainKey(payload.network) ?: run {
            log.warn("QuickNode webhook: unrecognised network '${payload.network}' — ignoring")
            return
        }

        val reader = solanaReader ?: run {
            log.warn("QuickNode webhook: no SolanaChainReader available for chainKey=$chainKey — ignoring payload sig=${payload.signature}")
            return
        }

        val existingCheckpoint = chainCheckpointRepository.findByChainKey(chainKey)?.lastBlock ?: 0L
        val watched = watchedAddressRepository.findByChainKey(chainKey)

        if (watched.isNotEmpty()) {
            val tx = reader.getTransaction(payload.signature)
            if (tx != null) {
                for (watchedAddress in watched) {
                    val transfer = reader.decodeTransfer(tx, payload.signature, payload.slot, watchedAddress) ?: continue
                    postTransactionUseCase.execute(buildCommand(transfer)).onFailure { error ->
                        log.error(
                            "QuickNode webhook: failed to post transfer idempotencyKey=${transfer.idempotencyKey}: ${error.message}"
                        )
                    }
                }
            } else {
                log.warn("QuickNode webhook: getTransaction returned null for sig=${payload.signature} — skipping decode, still advancing checkpoint")
            }
        }

        // Always advance checkpoint regardless of whether any transfer matched.
        // Prevents SolanaChainReader from re-scanning already-delivered slots on restart.
        val newCheckpoint = maxOf(existingCheckpoint, payload.slot)
        if (newCheckpoint > existingCheckpoint) {
            runCatching { chainCheckpointRepository.save(chainKey, newCheckpoint) }
                .onFailure {
                    log.error("QuickNode webhook: failed to advance checkpoint for $chainKey to $newCheckpoint", it)
                }
        }
    }

    private fun buildCommand(transfer: DetectedTransfer): PostTransactionCommand {
        val watched = transfer.watchedAddress
        return PostTransactionCommand(
            tenantId = TenantId.of(watched.tenantId),
            idempotencyKey = transfer.idempotencyKey,
            lines = listOf(
                JournalLineRequest(AccountId.of(watched.debitAccountId), EntryType.DEBIT, transfer.entry),
                JournalLineRequest(AccountId.of(watched.creditAccountId), EntryType.CREDIT, transfer.entry),
            ),
            createdBy = "quicknode-webhook",
        )
    }

    companion object {
        internal fun isValidSignature(secret: String, body: String, header: String): Boolean {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val expected = mac.doFinal(body.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                header.toByteArray(Charsets.UTF_8),
            )
        }

        internal fun networkToChainKey(network: String): String? = when (network.lowercase()) {
            "mainnet-beta" -> "SOLANA"
            else -> null
        }
    }
}
