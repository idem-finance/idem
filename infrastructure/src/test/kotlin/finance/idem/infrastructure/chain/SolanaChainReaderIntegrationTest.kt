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
import java.math.BigDecimal

class SolanaChainReaderIntegrationTest {

    private lateinit var wireMock: WireMockServer
    private lateinit var reader: SolanaChainReader

    private val usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val watchedWallet = "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS"
    private val signature = "5j7s6XxnkqxAbcDE1234567890abcdefghijklmnopqrstuvwxyz1234567"
    private val slot = 250_000_000L

    private val watched = WatchedAddress(
        chainKey = "SOLANA",
        walletAddress = watchedWallet,
        tokenContract = usdcMint,
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
        whenever(mockRepo.findByChainKey("SOLANA")).thenReturn(listOf(watched))

        reader = SolanaChainReader("http://localhost:${wireMock.port()}", mockRepo)
    }

    @AfterEach
    fun tearDown() {
        wireMock.stop()
    }

    @Test
    fun `poll returns DetectedTransfer for incoming USDC transfer`() {
        stubSignatures(signature, slot)
        stubTransaction(signature, slot, usdcMint, watchedWallet, preAmount = 0, postAmount = 1_000_000, decimals = 6)

        val result = reader.poll(249_999_999L)

        assertEquals(1, result.size)
        val transfer = result[0]
        assertEquals("SOLANA:$signature", transfer.idempotencyKey)
        assertEquals(MonetaryAmount.of(BigDecimal("1.000000")), transfer.entry.amount)
        assertEquals(StablecoinToken.USDC, transfer.entry.token)
        assertEquals(ChainId.SOLANA, transfer.entry.chainId)
        assertEquals(signature, transfer.entry.txHash)
        assertEquals(slot, transfer.entry.blockNumber)
        assertEquals(watchedWallet, transfer.entry.walletAddress)
        assertEquals(usdcMint, transfer.entry.tokenContract)
        assertEquals(watched, transfer.watchedAddress)
    }

    @Test
    fun `poll skips signatures at or before checkpoint slot`() {
        stubSignatures(signature, slot)
        stubTransaction(signature, slot, usdcMint, watchedWallet, preAmount = 0, postAmount = 1_000_000, decimals = 6)

        val result = reader.poll(slot)  // checkpoint == slot → filtered out

        assertEquals(emptyList<DetectedTransfer>(), result)
    }

    @Test
    fun `poll returns empty list when no signatures returned`() {
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getSignaturesForAddress"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"jsonrpc":"2.0","id":1,"result":[]}""")
                )
        )

        val result = reader.poll(0L)

        assertEquals(emptyList<DetectedTransfer>(), result)
    }

    @Test
    fun `poll skips failed transactions`() {
        stubSignatures(signature, slot)
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getTransaction"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(failedTransactionResponse(signature, slot))
                )
        )

        val result = reader.poll(0L)

        assertEquals(emptyList<DetectedTransfer>(), result)
    }

    @Test
    fun `poll skips signature with error in signatures list`() {
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getSignaturesForAddress"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(errorSignatureResponse(signature, slot))
                )
        )

        val result = reader.poll(0L)

        assertEquals(emptyList<DetectedTransfer>(), result)
    }

    private fun stubSignatures(sig: String, slot: Long) {
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getSignaturesForAddress"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(signaturesResponse(sig, slot))
                )
        )
    }

    private fun stubTransaction(
        sig: String,
        slot: Long,
        mint: String,
        recipientWallet: String,
        preAmount: Long,
        postAmount: Long,
        decimals: Int,
    ) {
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getTransaction"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(transactionResponse(sig, slot, mint, recipientWallet, preAmount, postAmount, decimals))
                )
        )
    }

    private fun signaturesResponse(sig: String, slot: Long) = """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": [
            {
              "signature": "$sig",
              "slot": $slot,
              "err": null,
              "blockTime": 1699000000,
              "confirmationStatus": "finalized"
            }
          ]
        }
    """.trimIndent()

    private fun errorSignatureResponse(sig: String, slot: Long) = """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": [
            {
              "signature": "$sig",
              "slot": $slot,
              "err": {"InstructionError": [0, "InvalidAccountData"]},
              "blockTime": 1699000000,
              "confirmationStatus": "finalized"
            }
          ]
        }
    """.trimIndent()

    private fun transactionResponse(
        sig: String,
        slot: Long,
        mint: String,
        recipientWallet: String,
        preAmount: Long,
        postAmount: Long,
        decimals: Int,
    ) = """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "slot": $slot,
            "transaction": {
              "signatures": ["$sig"],
              "message": {
                "accountKeys": ["$recipientWallet", "SenderWallet111111111111111111111111111111111"],
                "instructions": []
              }
            },
            "meta": {
              "fee": 5000,
              "err": null,
              "preBalances": [5000000, 2000000],
              "postBalances": [4995000, 2000000],
              "preTokenBalances": [
                {
                  "accountIndex": 1,
                  "mint": "$mint",
                  "uiTokenAmount": {
                    "amount": "5000000",
                    "decimals": $decimals,
                    "uiAmountString": "5"
                  },
                  "owner": "SenderWallet111111111111111111111111111111111"
                }
              ],
              "postTokenBalances": [
                {
                  "accountIndex": 1,
                  "mint": "$mint",
                  "uiTokenAmount": {
                    "amount": "4000000",
                    "decimals": $decimals,
                    "uiAmountString": "4"
                  },
                  "owner": "SenderWallet111111111111111111111111111111111"
                },
                {
                  "accountIndex": 2,
                  "mint": "$mint",
                  "uiTokenAmount": {
                    "amount": "$postAmount",
                    "decimals": $decimals,
                    "uiAmountString": "${postAmount.toBigDecimal().movePointLeft(decimals).toPlainString()}"
                  },
                  "owner": "$recipientWallet"
                }
              ]
            },
            "blockTime": 1699000000
          }
        }
    """.trimIndent()

    private fun failedTransactionResponse(sig: String, slot: Long) = """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "slot": $slot,
            "transaction": {
              "signatures": ["$sig"],
              "message": {"accountKeys": [], "instructions": []}
            },
            "meta": {
              "fee": 5000,
              "err": {"InstructionError": [0, "InvalidAccountData"]},
              "preTokenBalances": [],
              "postTokenBalances": []
            }
          }
        }
    """.trimIndent()
}
