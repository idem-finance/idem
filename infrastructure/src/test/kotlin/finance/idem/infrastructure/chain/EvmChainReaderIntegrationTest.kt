package finance.idem.infrastructure.chain

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import java.math.BigDecimal

class EvmChainReaderIntegrationTest {
    private lateinit var wireMock: WireMockServer
    private lateinit var reader: EvmChainReader

    private val usdcContract = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val watchedWallet = "0xabcdef1234567890abcdef1234567890abcdef34"
    private val txHash = "0xabc123def456abc123def456abc123def456abc123def456abc123def456abc1"

    private val watched =
        WatchedAddress(
            chainKey = "EVM_1",
            walletAddress = watchedWallet,
            tokenContract = usdcContract,
            token = StablecoinToken.USDC,
            tenantId = "tenant-1",
            debitAccountId = "debit-1",
            creditAccountId = "credit-1",
        )

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()

        val mockRepo = mock<WatchedAddressRepository>()
        whenever(mockRepo.findByChainKey("EVM_1")).thenReturn(listOf(watched))

        val web3j = Web3j.build(HttpService("http://localhost:${wireMock.port()}"))
        reader =
            EvmChainReader(
                chainKey = "EVM_1",
                web3j = web3j,
                watchedAddressRepository = mockRepo,
                maxBlockRange = 1_000_000L,
            )
    }

    @AfterEach
    fun tearDown() {
        wireMock.stop()
    }

    @Test
    fun `poll returns OnChainEntry for matching ERC20 Transfer log`() {
        stubBlockNumber("0x12a05f2") // 19_531_250
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing(""""method":"eth_getLogs""""))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(alchemyEthGetLogsResponse(txHash, watchedWallet, usdcContract)),
                ),
        )

        val result = reader.poll(19_000_000L)

        assertEquals(1, result.size)
        val transfer = result[0]
        assertEquals("EVM_1:$txHash:0", transfer.idempotencyKey)
        assertEquals(MonetaryAmount.of(BigDecimal("1.000000")), transfer.entry.amount)
        assertEquals(StablecoinToken.USDC, transfer.entry.token)
        assertEquals(ChainId.EVM, transfer.entry.chainId)
        assertEquals(txHash, transfer.entry.txHash)
        assertEquals(19_531_250L, transfer.entry.blockNumber) // 0x12a05f2
        assertEquals(watchedWallet.lowercase(), transfer.entry.walletAddress)
        assertEquals(usdcContract.lowercase(), transfer.entry.tokenContract)
        assertEquals(watched, transfer.watchedAddress)
    }

    @Test
    fun `poll returns empty list when no logs match watched address`() {
        stubBlockNumber("0x12a05f2")
        val unrelatedWallet = "0xffffffffffffffffffffffffffffffffffffffff"
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing(""""method":"eth_getLogs""""))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(alchemyEthGetLogsResponse(txHash, unrelatedWallet, usdcContract)),
                ),
        )

        val result = reader.poll(19_000_000L)

        assertEquals(emptyList<DetectedTransfer>(), result)
    }

    @Test
    fun `poll returns empty list when Alchemy returns empty result`() {
        stubBlockNumber("0x12a05f2")
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing(""""method":"eth_getLogs""""))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"jsonrpc":"2.0","id":1,"result":[]}"""),
                ),
        )

        val result = reader.poll(19_000_000L)

        assertEquals(emptyList<DetectedTransfer>(), result)
    }

    private fun stubBlockNumber(hex: String) {
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing(""""method":"eth_blockNumber""""))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"jsonrpc":"2.0","id":1,"result":"$hex"}"""),
                ),
        )
    }

    private fun alchemyEthGetLogsResponse(
        txHash: String,
        toWallet: String,
        contractAddress: String,
    ): String {
        val toPadded = "0x000000000000000000000000${toWallet.removePrefix("0x").lowercase()}"
        val fromPadded = "0x000000000000000000000000abcdef1234567890abcdef1234567890abcdef12"
        // 1_000_000 = 0xF4240 → 1 USDC (6 decimals)
        val oneUsdc = "0x00000000000000000000000000000000000000000000000000000000000f4240"

        return """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "result": [
                {
                  "address": "${contractAddress.lowercase()}",
                  "topics": [
                    "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                    "$fromPadded",
                    "$toPadded"
                  ],
                  "data": "$oneUsdc",
                  "blockNumber": "0x12a05f2",
                  "transactionHash": "$txHash",
                  "transactionIndex": "0x0",
                  "blockHash": "0x0000000000000000000000000000000000000000000000000000000000000001",
                  "logIndex": "0x0",
                  "removed": false
                }
              ]
            }
            """.trimIndent()
    }
}
