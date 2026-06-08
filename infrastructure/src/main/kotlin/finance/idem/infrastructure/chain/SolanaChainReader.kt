package finance.idem.infrastructure.chain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.monetary.OnChainEntry
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class SolanaChainReader(
    private val rpcUrl: String,
    private val watchedAddressRepository: WatchedAddressRepository,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : ChainReader {

    override val chainKey = "SOLANA"

    override fun poll(checkpoint: Long): List<DetectedTransfer> {
        val watched = watchedAddressRepository.findByChainKey(chainKey)
        if (watched.isEmpty()) return emptyList()

        return watched.flatMap { watchedAddress ->
            getSignaturesForAddress(watchedAddress.walletAddress)
                .filter { it.slot > checkpoint && it.err == null }
                .mapNotNull { sigInfo ->
                    val tx = getTransaction(sigInfo.signature) ?: return@mapNotNull null
                    decodeTransfer(tx, sigInfo.signature, sigInfo.slot, watchedAddress)
                }
        }
    }

    internal fun decodeTransfer(
        tx: SolanaTransactionResult,
        signature: String,
        slot: Long,
        watchedAddress: WatchedAddress,
    ): DetectedTransfer? {
        val meta = tx.meta ?: return null
        if (meta.err != null) return null

        val postBalances = meta.postTokenBalances ?: return null
        val preBalances = meta.preTokenBalances ?: emptyList()

        val receiving = postBalances.firstOrNull { post ->
            post.owner?.equals(watchedAddress.walletAddress, ignoreCase = true) == true &&
                post.mint.equals(watchedAddress.tokenContract, ignoreCase = true)
        } ?: return null

        val postAmount = receiving.uiTokenAmount.amount.toLongOrNull() ?: return null
        val preAmount = preBalances
            .firstOrNull { it.accountIndex == receiving.accountIndex }
            ?.uiTokenAmount?.amount?.toLongOrNull() ?: 0L
        val delta = postAmount - preAmount

        if (delta <= 0) return null

        val amount = MonetaryAmount.of(BigDecimal(delta).movePointLeft(receiving.uiTokenAmount.decimals))

        return DetectedTransfer(
            idempotencyKey = "$chainKey:$signature",
            entry = OnChainEntry(
                amount = amount,
                token = watchedAddress.token,
                chainId = ChainId.SOLANA,
                txHash = signature,
                blockNumber = slot,
                walletAddress = watchedAddress.walletAddress,
                tokenContract = watchedAddress.tokenContract,
            ),
            watchedAddress = watchedAddress,
        )
    }

    private fun getSignaturesForAddress(address: String, limit: Int = 100): List<SolanaSignatureInfo> {
        val body = """{"jsonrpc":"2.0","id":1,"method":"getSignaturesForAddress","params":["$address",{"limit":$limit}]}"""
        val response = rpcPost(body) ?: return emptyList()
        return runCatching {
            MAPPER.readValue(response, SolanaSignaturesResponse::class.java).result ?: emptyList()
        }.getOrElse {
            log.warn("Failed to parse getSignaturesForAddress response: ${it.message}")
            emptyList()
        }
    }

    private fun getTransaction(signature: String): SolanaTransactionResult? {
        val body = """{"jsonrpc":"2.0","id":1,"method":"getTransaction","params":["$signature",{"encoding":"json","commitment":"confirmed","maxSupportedTransactionVersion":0}]}"""
        val response = rpcPost(body) ?: return null
        return runCatching {
            MAPPER.readValue(response, SolanaTransactionResponse::class.java).result
        }.getOrElse {
            log.warn("Failed to parse getTransaction response: ${it.message}")
            null
        }
    }

    private fun rpcPost(jsonBody: String): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(rpcUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) response.body()
            else {
                log.warn("Solana RPC returned HTTP ${response.statusCode()}")
                null
            }
        } catch (e: Exception) {
            log.warn("Solana RPC call failed: ${e.message}")
            null
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class SolanaSignaturesResponse(val result: List<SolanaSignatureInfo>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class SolanaSignatureInfo(
        val signature: String = "",
        val slot: Long = 0,
        val err: Any? = null,
        val blockTime: Long? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class SolanaTransactionResponse(val result: SolanaTransactionResult? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class SolanaTransactionResult(
        val slot: Long = 0,
        val meta: SolanaTransactionMeta? = null,
        val blockTime: Long? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class SolanaTransactionMeta(
        val err: Any? = null,
        val preTokenBalances: List<SolanaTokenBalance>? = null,
        val postTokenBalances: List<SolanaTokenBalance>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class SolanaTokenBalance(
        val accountIndex: Int = 0,
        val mint: String = "",
        val owner: String? = null,
        val uiTokenAmount: SolanaUiTokenAmount = SolanaUiTokenAmount(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class SolanaUiTokenAmount(
        val amount: String = "0",
        val decimals: Int = 0,
        val uiAmountString: String = "0",
    )

    companion object {
        private val log = LoggerFactory.getLogger(SolanaChainReader::class.java)
        internal val MAPPER: ObjectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }
}
