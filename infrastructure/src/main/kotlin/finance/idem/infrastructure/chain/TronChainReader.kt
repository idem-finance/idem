package finance.idem.infrastructure.chain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.monetary.OnChainEntry
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class TronChainReader(
    private val apiUrl: String,
    private val watchedAddressRepository: WatchedAddressRepository,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val requestDelayMs: Long = REQUEST_DELAY_MS,
    private val pageSize: Int = PAGE_SIZE,
) : ChainReader, Closeable {

    override val chainKey = "TRON"

    override fun close() = httpClient.close()

    override fun poll(checkpoint: Long): List<DetectedTransfer> {
        val watched = watchedAddressRepository.findByChainKey(chainKey)
        if (watched.isEmpty()) return emptyList()
        return watched.flatMap { fetchTransfers(it, checkpoint) }
    }

    private fun fetchTransfers(watchedAddress: WatchedAddress, checkpoint: Long): List<DetectedTransfer> {
        val collected = mutableListOf<TronTransfer>()
        var start = 0

        while (true) {
            val page = fetchPage(watchedAddress.walletAddress, watchedAddress.tokenContract, start)
            sleepForRateLimit()

            if (page.isEmpty()) break

            // Tronscan returns newest-first; stop collecting once we hit the checkpoint boundary
            val relevant = page.takeWhile { it.blockId > checkpoint }
            collected += relevant

            if (relevant.size < page.size || page.size < pageSize) break
            start += pageSize
        }

        return collected
            .sortedBy { it.blockId }
            .mapNotNull { decodeTransfer(it, watchedAddress) }
    }

    internal fun decodeTransfer(transfer: TronTransfer, watchedAddress: WatchedAddress): DetectedTransfer? {
        if (!transfer.toAddress.equals(watchedAddress.walletAddress, ignoreCase = true)) return null
        if (!transfer.tokenInfo.tokenId.equals(watchedAddress.tokenContract, ignoreCase = true)) return null
        if (transfer.finalResult != null && transfer.finalResult != "SUCCESS") return null

        val decimals = decimalsFor(watchedAddress.token) ?: run {
            log.error("Unsupported token ${watchedAddress.token} for Tron reader in tx=${transfer.txHash} — skipping")
            return null
        }
        if (transfer.tokenInfo.decimals != decimals) {
            log.error(
                "Unexpected decimals ${transfer.tokenInfo.decimals} for ${watchedAddress.tokenContract} " +
                    "in tx=${transfer.txHash} (expected $decimals) — skipping"
            )
            return null
        }

        val rawAmount = transfer.quant.toLongOrNull() ?: run {
            log.warn("Unparseable amount '${transfer.quant}' in tx=${transfer.txHash} — skipping")
            return null
        }
        if (rawAmount <= 0) return null

        val amount = MonetaryAmount.of(BigDecimal(rawAmount).movePointLeft(decimals))

        return DetectedTransfer(
            idempotencyKey = "$chainKey:${transfer.txHash}",
            entry = OnChainEntry(
                amount = amount,
                token = watchedAddress.token,
                chainId = ChainId.TRON,
                txHash = transfer.txHash,
                blockNumber = transfer.blockId,
                walletAddress = transfer.toAddress.lowercase(),
                tokenContract = transfer.tokenInfo.tokenId.lowercase(),
                fromAddress = transfer.fromAddress.takeIf { it.isNotBlank() }?.lowercase(),
            ),
            watchedAddress = watchedAddress,
        )
    }

    private fun fetchPage(address: String, tokenContract: String, start: Int): List<TronTransfer> {
        val url = "$apiUrl/api/token_trc20/transfers" +
            "?relatedAddress=$address" +
            "&token_address=$tokenContract" +
            "&start=$start" +
            "&limit=$pageSize"
        val response = httpGet(url) ?: return emptyList()
        return runCatching {
            MAPPER.readValue(response, TronTransferResponse::class.java).tokenTransfers
        }.getOrElse {
            log.warn("Failed to parse Tronscan response: ${it.message}")
            emptyList()
        }
    }

    private fun httpGet(url: String): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) response.body()
            else {
                log.warn("Tronscan API returned HTTP ${response.statusCode()} for url=$url")
                null
            }
        } catch (e: Exception) {
            log.warn("Tronscan API call failed: ${e.message}")
            null
        }
    }

    private fun sleepForRateLimit() {
        if (requestDelayMs > 0) Thread.sleep(requestDelayMs)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class TronTransferResponse(
        @JsonProperty("token_transfers") val tokenTransfers: List<TronTransfer> = emptyList(),
        val total: Long = 0,
        val rangeTotal: Long = 0,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class TronTransfer(
        @JsonProperty("transaction_id") val txHash: String = "",
        @JsonProperty("block_id") val blockId: Long = 0,
        @JsonProperty("block_ts") val blockTs: Long = 0,
        @JsonProperty("from_address") val fromAddress: String = "",
        @JsonProperty("to_address") val toAddress: String = "",
        val quant: String = "0",
        val finalResult: String? = null,
        @JsonProperty("token_info") val tokenInfo: TronTokenInfo = TronTokenInfo(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class TronTokenInfo(
        @JsonProperty("tokenId") val tokenId: String = "",
        val symbol: String = "",
        val decimals: Int = 6,
    )

    companion object {
        private val log = LoggerFactory.getLogger(TronChainReader::class.java)
        private const val REQUEST_DELAY_MS = 200L
        private const val PAGE_SIZE = 50

        private val MAPPER: ObjectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        private fun decimalsFor(token: StablecoinToken): Int? = when (token) {
            StablecoinToken.USDT -> 6
            StablecoinToken.USDC -> 6
            else -> null
        }
    }
}
