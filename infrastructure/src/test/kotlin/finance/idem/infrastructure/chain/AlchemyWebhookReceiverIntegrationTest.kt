package finance.idem.infrastructure.chain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.core.StablecoinToken
import finance.idem.core.TransactionId
import finance.idem.core.chain.ChainCheckpoint
import finance.idem.core.chain.ChainCheckpointRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AlchemyWebhookReceiverIntegrationTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var watchedAddressRepository: WatchedAddressRepository
    private lateinit var checkpointRepository: ChainCheckpointRepository
    private lateinit var postTransactionUseCase: PostTransactionUseCase

    private val signingKey = "test-webhook-signing-key"
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private val usdcContract = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val watchedWallet = "0xabcdef1234567890abcdef1234567890abcdef34"
    private val txHash = "0xabc123def456abc123def456abc123def456abc123def456abc123def456abc1"
    private val tenantId = "00000000-0000-0000-0000-000000000001"
    private val debitAccountId = "00000000-0000-0000-0000-000000000002"
    private val creditAccountId = "00000000-0000-0000-0000-000000000003"

    private val watchedAddress = WatchedAddress(
        chainKey = "EVM_1",
        walletAddress = watchedWallet,
        tokenContract = usdcContract,
        token = StablecoinToken.USDC,
        tenantId = tenantId,
        debitAccountId = debitAccountId,
        creditAccountId = creditAccountId,
    )

    @BeforeEach
    fun setUp() {
        watchedAddressRepository = mock()
        checkpointRepository = mock()
        postTransactionUseCase = mock()

        val receiver = AlchemyWebhookReceiver(
            watchedAddressRepository = watchedAddressRepository,
            chainCheckpointRepository = checkpointRepository,
            postTransactionUseCase = postTransactionUseCase,
            objectMapper = objectMapper,
            config = ChainConfig(alchemyWebhookSigningKey = signingKey),
        )
        mockMvc = MockMvcBuilders.standaloneSetup(receiver).build()
    }

    @Test
    fun `returns 401 when X-Alchemy-Signature header is missing and signing key is configured`() {
        mockMvc.perform(
            post("/internal/webhooks/alchemy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildPayload(txHash, watchedWallet, usdcContract))
        ).andExpect(status().isUnauthorized)

        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `returns 401 when X-Alchemy-Signature is wrong`() {
        mockMvc.perform(
            post("/internal/webhooks/alchemy")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Alchemy-Signature", "deadbeef0000")
                .content(buildPayload(txHash, watchedWallet, usdcContract))
        ).andExpect(status().isUnauthorized)

        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `returns 200 when signing key is blank (dev mode — skips HMAC validation)`() {
        val receiver = AlchemyWebhookReceiver(
            watchedAddressRepository = watchedAddressRepository,
            chainCheckpointRepository = checkpointRepository,
            postTransactionUseCase = postTransactionUseCase,
            objectMapper = objectMapper,
            config = ChainConfig(alchemyWebhookSigningKey = ""),
        )
        val devMockMvc = MockMvcBuilders.standaloneSetup(receiver).build()

        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(emptyList())

        devMockMvc.perform(
            post("/internal/webhooks/alchemy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildPayload(txHash, watchedWallet, usdcContract))
        ).andExpect(status().isOk)
    }

    @Test
    fun `returns 200 and ignores non-ADDRESS_ACTIVITY webhooks`() {
        val body = """{"type":"NFT_ACTIVITY","event":{"network":"ETH_MAINNET","activity":[]}}"""

        mockMvc.perform(
            post("/internal/webhooks/alchemy")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Alchemy-Signature", computeHmac(signingKey, body))
                .content(body)
        ).andExpect(status().isOk)

        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `returns 200 and ignores unknown network`() {
        val body = """{"type":"ADDRESS_ACTIVITY","event":{"network":"UNKNOWN_CHAIN","activity":[]}}"""

        mockMvc.perform(
            post("/internal/webhooks/alchemy")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Alchemy-Signature", computeHmac(signingKey, body))
                .content(body)
        ).andExpect(status().isOk)

        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `posts transaction and advances checkpoint for valid USDC transfer`() {
        val body = buildPayload(txHash, watchedWallet, usdcContract, rawValue = "0x000f4240") // 1 USDC

        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(
            ChainCheckpoint("EVM_1", 19_000_000L, Instant.now())
        )
        whenever(postTransactionUseCase.execute(any())).thenReturn(
            Result.success(TransactionId(UUID.randomUUID()))
        )

        mockMvc.perform(
            post("/internal/webhooks/alchemy")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Alchemy-Signature", computeHmac(signingKey, body))
                .content(body)
        ).andExpect(status().isOk)

        val cmdCaptor = argumentCaptor<PostTransactionCommand>()
        verify(postTransactionUseCase).execute(cmdCaptor.capture())
        val cmd = cmdCaptor.firstValue

        assertEquals("EVM_1:$txHash:0", cmd.idempotencyKey)
        assertEquals(tenantId, cmd.tenantId.value.toString())
        assertEquals("alchemy-webhook", cmd.createdBy)
        assertEquals(2, cmd.lines.size)

        verify(checkpointRepository).save("EVM_1", 19_531_250L) // 0x12a05f2
    }

    @Test
    fun `skips activity where toAddress is not watched`() {
        val body = buildPayload(txHash, "0xffffffffffffffffffffffffffffffffffffffff", usdcContract)

        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(null)

        mockMvc.perform(
            post("/internal/webhooks/alchemy")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Alchemy-Signature", computeHmac(signingKey, body))
                .content(body)
        ).andExpect(status().isOk)

        verify(postTransactionUseCase, never()).execute(any())
    }

    private fun buildPayload(
        txHash: String,
        toAddress: String,
        contract: String,
        rawValue: String = "0x000f4240",
        blockNum: String = "0x12a05f2",
        logIndex: String = "0x0",
        network: String = "ETH_MAINNET",
    ): String = """
        {
          "webhookId": "wh_test",
          "id": "whevt_test",
          "createdAt": "2024-01-01T00:00:00.000Z",
          "type": "ADDRESS_ACTIVITY",
          "event": {
            "network": "$network",
            "activity": [
              {
                "fromAddress": "0xfrom",
                "toAddress": "$toAddress",
                "blockNum": "$blockNum",
                "hash": "$txHash",
                "value": 1.0,
                "asset": "USDC",
                "category": "token",
                "rawContract": {
                  "rawValue": "$rawValue",
                  "address": "$contract",
                  "decimals": 6
                },
                "log": {
                  "logIndex": "$logIndex",
                  "transactionHash": "$txHash",
                  "blockNumber": "$blockNum",
                  "address": "$contract",
                  "data": "$rawValue",
                  "topics": [],
                  "removed": false
                }
              }
            ]
          }
        }
    """.trimIndent()

    private fun computeHmac(key: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
