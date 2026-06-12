package finance.idem.infrastructure.chain

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class TronChainReaderIntegrationTest {

    private lateinit var wireMock: WireMockServer
    private lateinit var reader: TronChainReader

    private val usdtContract = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
    private val watchedWallet = "TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7"
    private val senderWallet  = "TJCnKsPa7y5okkXvQAidZBzqx3QyQ6sxMW"
    private val txHash        = "abc123def456abc123def456abc123def456abc123def456abc123def456abc1"
    private val blockId       = 55_000_000L

    private val watched = WatchedAddress(
        chainKey = "TRON",
        walletAddress = watchedWallet,
        tokenContract = usdtContract,
        token = StablecoinToken.USDT,
        tenantId = "tenant-1",
        debitAccountId = "debit-1",
        creditAccountId = "credit-1",
    )

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()

        val mockRepo = mock<WatchedAddressRepository>()
        whenever(mockRepo.findByChainKey("TRON")).thenReturn(listOf(watched))

        reader = TronChainReader(
            apiUrl = "http://localhost:${wireMock.port()}",
            watchedAddressRepository = mockRepo,
            requestDelayMs = 0,
            pageSize = 2,
        )
    }

    @AfterEach
    fun tearDown() {
        wireMock.stop()
    }

    @Test
    fun `poll returns DetectedTransfer for incoming USDT transfer`() {
        stubTransfers(transfersResponse(txHash, blockId, senderWallet, watchedWallet, usdtContract, "1000000"))

        val result = reader.poll(blockId - 1)

        assertEquals(1, result.size)
        val transfer = result[0]
        assertEquals("TRON:$txHash", transfer.idempotencyKey)
        assertEquals(MonetaryAmount.of(BigDecimal("1.000000")), transfer.entry.amount)
        assertEquals(StablecoinToken.USDT, transfer.entry.token)
        assertEquals(ChainId.TRON, transfer.entry.chainId)
        assertEquals(txHash, transfer.entry.txHash)
        assertEquals(blockId, transfer.entry.blockNumber)
        assertEquals(watchedWallet.lowercase(), transfer.entry.walletAddress)
        assertEquals(usdtContract.lowercase(), transfer.entry.tokenContract)
        assertEquals(watched, transfer.watchedAddress)
    }

    @Test
    fun `poll skips transfers at or before checkpoint block`() {
        stubTransfers(transfersResponse(txHash, blockId, senderWallet, watchedWallet, usdtContract, "1000000"))

        val result = reader.poll(blockId)

        assertEquals(emptyList<DetectedTransfer>(), result)
    }

    @Test
    fun `poll returns empty list when Tronscan returns no transfers`() {
        stubTransfers(emptyTransfersResponse())

        assertEquals(emptyList<DetectedTransfer>(), reader.poll(0L))
    }

    @Test
    fun `poll skips outgoing transfers — to address does not match watched wallet`() {
        stubTransfers(transfersResponse(txHash, blockId, watchedWallet, senderWallet, usdtContract, "1000000"))

        assertEquals(emptyList<DetectedTransfer>(), reader.poll(0L))
    }

    @Test
    fun `poll skips failed transfers`() {
        stubTransfers(
            transfersResponse(
                txHash, blockId, senderWallet, watchedWallet, usdtContract, "1000000",
                finalResult = "FAILED",
            )
        )

        assertEquals(emptyList<DetectedTransfer>(), reader.poll(0L))
    }

    @Test
    fun `poll paginates when first page is full — fetches next page with offset`() {
        val tx1 = "tx1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val tx2 = "tx2bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val tx3 = "tx3ccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

        // Page 1 (start=0): exactly pageSize(2) items above checkpoint
        wireMock.stubFor(
            get(urlPathEqualTo("/api/token_trc20/transfers"))
                .withQueryParam("start", equalTo("0"))
                .inScenario("pagination")
                .whenScenarioStateIs(STARTED)
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(twoTransfersResponse(tx1, blockId + 2, tx2, blockId + 1, senderWallet))
                )
                .willSetStateTo("page2")
        )
        // Page 2 (start=2): 1 item at checkpoint boundary → stops pagination
        wireMock.stubFor(
            get(urlPathEqualTo("/api/token_trc20/transfers"))
                .withQueryParam("start", equalTo("2"))
                .inScenario("pagination")
                .whenScenarioStateIs("page2")
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(transfersResponse(tx3, blockId, senderWallet, watchedWallet, usdtContract, "500000"))
                )
        )

        val result = reader.poll(blockId)

        // tx3 is at checkpoint, not above it → filtered; tx1 and tx2 returned sorted oldest-first
        assertEquals(2, result.size)
        assertEquals(blockId + 1, result[0].entry.blockNumber)
        assertEquals(blockId + 2, result[1].entry.blockNumber)
    }

    @Test
    fun `poll sends TRON-PRO-API-KEY header when api key is configured`() {
        stubTransfers(transfersResponse(txHash, blockId, senderWallet, watchedWallet, usdtContract, "1000000"))

        val mockRepo = mock<WatchedAddressRepository>()
        whenever(mockRepo.findByChainKey("TRON")).thenReturn(listOf(watched))
        val readerWithApiKey = TronChainReader(
            apiUrl = "http://localhost:${wireMock.port()}",
            watchedAddressRepository = mockRepo,
            requestDelayMs = 0,
            pageSize = 2,
            apiKey = "test-tron-key",
        )

        readerWithApiKey.poll(blockId - 1)

        wireMock.verify(
            getRequestedFor(urlPathEqualTo("/api/token_trc20/transfers"))
                .withHeader("TRON-PRO-API-KEY", equalTo("test-tron-key"))
        )
    }

    @Test
    fun `poll does not send TRON-PRO-API-KEY header when api key is blank`() {
        stubTransfers(transfersResponse(txHash, blockId, senderWallet, watchedWallet, usdtContract, "1000000"))

        reader.poll(blockId - 1)

        wireMock.verify(
            getRequestedFor(urlPathEqualTo("/api/token_trc20/transfers"))
                .withoutHeader("TRON-PRO-API-KEY")
        )
    }

    @Test
    fun `poll results are sorted ascending by block number`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/api/token_trc20/transfers"))
                .withQueryParam("start", equalTo("0"))
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            twoTransfersResponse(
                                "txNew", blockId + 10, "txOld", blockId + 1, senderWallet,
                            )
                        )
                )
        )

        val result = reader.poll(blockId)

        assertEquals(blockId + 1, result[0].entry.blockNumber)
        assertEquals(blockId + 10, result[1].entry.blockNumber)
    }

    // --- helpers ---

    private fun stubTransfers(body: String) {
        wireMock.stubFor(
            get(urlPathEqualTo("/api/token_trc20/transfers"))
                .withQueryParam("relatedAddress", equalTo(watchedWallet))
                .withQueryParam("token_address", equalTo(usdtContract))
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)
                )
        )
    }

    private fun transfersResponse(
        tx: String,
        block: Long,
        from: String,
        to: String,
        contract: String,
        quant: String,
        finalResult: String = "SUCCESS",
    ) = """
        {
          "total": 1,
          "rangeTotal": 1,
          "token_transfers": [
            {
              "transaction_id": "$tx",
              "block_id": $block,
              "block_ts": 1699000000000,
              "from_address": "$from",
              "to_address": "$to",
              "quant": "$quant",
              "finalResult": "$finalResult",
              "token_info": {
                "tokenId": "$contract",
                "symbol": "USDT",
                "decimals": 6
              }
            }
          ]
        }
    """.trimIndent()

    private fun twoTransfersResponse(
        tx1: String, block1: Long,
        tx2: String, block2: Long,
        from: String,
    ) = """
        {
          "total": 2,
          "rangeTotal": 2,
          "token_transfers": [
            {
              "transaction_id": "$tx1",
              "block_id": $block1,
              "block_ts": 1699000000001,
              "from_address": "$from",
              "to_address": "$watchedWallet",
              "quant": "1000000",
              "finalResult": "SUCCESS",
              "token_info": { "tokenId": "$usdtContract", "symbol": "USDT", "decimals": 6 }
            },
            {
              "transaction_id": "$tx2",
              "block_id": $block2,
              "block_ts": 1699000000000,
              "from_address": "$from",
              "to_address": "$watchedWallet",
              "quant": "2000000",
              "finalResult": "SUCCESS",
              "token_info": { "tokenId": "$usdtContract", "symbol": "USDT", "decimals": 6 }
            }
          ]
        }
    """.trimIndent()

    private fun emptyTransfersResponse() = """
        {"total": 0, "rangeTotal": 0, "token_transfers": []}
    """.trimIndent()
}
