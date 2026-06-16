package finance.idem.infrastructure.chain

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

        // signaturePageSize=2 lets the pagination test trigger a second page fetch with a small fixture
        reader = SolanaChainReader("http://localhost:${wireMock.port()}", mockRepo, signaturePageSize = 2)
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
        assertEquals("SOLANA:$signature:2", transfer.idempotencyKey)
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
    fun `poll paginates when first page is full — fetches subsequent pages with before cursor`() {
        val sig1 = "Sig1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val sig2 = "Sig2bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val sig3 = "Sig3ccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

        // Page 1: exactly signaturePageSize(2) items → triggers pagination
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getSignaturesForAddress"))
                .inScenario("sig-pagination")
                .whenScenarioStateIs(STARTED)
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(twoSignaturesResponse(sig1, 300L, sig2, 200L))
                )
                .willSetStateTo("page2")
        )
        // Page 2: 1 item at slot=50 below checkpoint=100 → stops pagination
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getSignaturesForAddress"))
                .inScenario("sig-pagination")
                .whenScenarioStateIs("page2")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(signaturesResponse(sig3, 50L))
                )
        )
        // poll() batches sig2+sig1 in one request (sorted oldest-first, so id=0→sig2, id=1→sig1)
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getTransaction"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            transactionBatchResponse(
                                transactionResultJson(sig2, 200L, usdcMint, watchedWallet, 0, 1_000_000, 6),
                                transactionResultJson(sig1, 300L, usdcMint, watchedWallet, 0, 1_000_000, 6),
                            )
                        )
                )
        )

        val result = reader.poll(100L)

        // sig3 (slot=50) is below checkpoint; only sig1 and sig2 are returned, sorted oldest-first
        assertEquals(2, result.size)
        assertEquals(200L, result[0].entry.blockNumber)
        assertEquals(300L, result[1].entry.blockNumber)
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

    @Test
    fun `poll sends all getTransaction calls in a single batched request per poll cycle`() {
        val sigOld = "SigOldAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val sigNew = "SigNewBbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val mockRepo = mock<WatchedAddressRepository>()
        whenever(mockRepo.findByChainKey("SOLANA")).thenReturn(listOf(watched))
        val batchReader = SolanaChainReader("http://localhost:${wireMock.port()}", mockRepo, signaturePageSize = 10)

        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getSignaturesForAddress"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(twoSignaturesResponse(sigNew, 200L, sigOld, 100L))
                )
        )
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getTransaction"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            transactionBatchResponse(
                                transactionResultJson(sigOld, 100L, usdcMint, watchedWallet, 0, 1_000_000, 6),
                                transactionResultJson(sigNew, 200L, usdcMint, watchedWallet, 0, 2_000_000, 6),
                            )
                        )
                )
        )

        val result = batchReader.poll(0L)

        assertEquals(2, result.size)
        assertEquals(100L, result[0].entry.blockNumber)
        assertEquals(200L, result[1].entry.blockNumber)

        val txRequests = wireMock.findAll(
            postRequestedFor(urlPathEqualTo("/")).withRequestBody(containing("getTransaction"))
        )
        assertEquals(1, txRequests.size, "expected exactly one batched getTransaction request per poll cycle")
        val body = txRequests[0].bodyAsString
        assertTrue(body.trimStart().startsWith("["), "batch request body must be a JSON array")
        assertTrue(body.contains("\"id\":0"), "request must include id=0")
        assertTrue(body.contains("\"id\":1"), "request must include id=1")
    }

    @Test
    fun `poll splits getTransaction into multiple requests when signature count exceeds transactionBatchSize`() {
        val sig1 = "Sig1ChunkAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val sig2 = "Sig2ChunkBbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val sig3 = "Sig3ChunkCccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

        val mockRepo = mock<WatchedAddressRepository>()
        whenever(mockRepo.findByChainKey("SOLANA")).thenReturn(listOf(watched))
        val batchReader = SolanaChainReader(
            "http://localhost:${wireMock.port()}", mockRepo,
            signaturePageSize = 10, transactionBatchSize = 2,
        )

        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getSignaturesForAddress"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(signaturesResponseFor(sig1 to 100L, sig2 to 200L, sig3 to 300L))
                )
        )
        // Match each chunk by its distinct signature so each gets the correct response data.
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing(sig1))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            transactionBatchResponse(
                                transactionResultJson(sig1, 100L, usdcMint, watchedWallet, 0, 1_000_000, 6),
                                transactionResultJson(sig2, 200L, usdcMint, watchedWallet, 0, 2_000_000, 6),
                            )
                        )
                )
        )
        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing(sig3))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            transactionBatchResponse(
                                transactionResultJson(sig3, 300L, usdcMint, watchedWallet, 0, 3_000_000, 6),
                            )
                        )
                )
        )

        val result = batchReader.poll(0L)

        assertEquals(3, result.size)
        assertEquals(100L, result[0].entry.blockNumber)
        assertEquals(200L, result[1].entry.blockNumber)
        assertEquals(300L, result[2].entry.blockNumber)

        val txRequests = wireMock.findAll(
            postRequestedFor(urlPathEqualTo("/")).withRequestBody(containing("getTransaction"))
        )
        assertEquals(2, txRequests.size, "expected one request per batch chunk: chunk(2) + chunk(1)")
    }

    @Test
    fun `getTransactionBatch maps responses to signatures by id, returning null for missing entries`() {
        val sig1 = "Sig1DirectAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val sig2 = "Sig2DirectBbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val sigMissing = "SigMissingCccccccccccccccccccccccccccccccccccccccccccccccccccccc"

        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getTransaction"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            transactionBatchResponse(
                                transactionResultJson(sig1, 100L, usdcMint, watchedWallet, 0, 1_000_000, 6),
                                transactionResultJson(sig2, 200L, usdcMint, watchedWallet, 0, 2_000_000, 6),
                                // id=2 (sigMissing) intentionally absent from response
                            )
                        )
                )
        )

        val results = reader.getTransactionBatch(listOf(sig1, sig2, sigMissing))

        assertEquals(3, results.size)
        assertNotNull(results[sig1])
        assertNotNull(results[sig2])
        assertNull(results[sigMissing])

        val transfer1 = reader.decodeTransfer(results[sig1]!!, sig1, 100L, watched)
        assertEquals(MonetaryAmount.of(BigDecimal("1.000000")), transfer1?.entry?.amount)

        val transfer2 = reader.decodeTransfer(results[sig2]!!, sig2, 200L, watched)
        assertEquals(MonetaryAmount.of(BigDecimal("2.000000")), transfer2?.entry?.amount)
    }

    @Test
    fun `getTransactionBatch returns all-null map when RPC returns non-200 for batch request`() {
        val sig1 = "SigHttpErrAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val sig2 = "SigHttpErrBbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getTransaction"))
                .willReturn(aResponse().withStatus(500))
        )

        val results = reader.getTransactionBatch(listOf(sig1, sig2))

        assertEquals(2, results.size)
        assertNull(results[sig1])
        assertNull(results[sig2])
    }

    @Test
    fun `getTransactionBatch returns all-null map when RPC response body is not a JSON array`() {
        val sig1 = "SigBadJsonAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val sig2 = "SigBadJsonBbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        wireMock.stubFor(
            post(urlPathEqualTo("/"))
                .withRequestBody(containing("getTransaction"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Internal error"}}""")
                )
        )

        val results = reader.getTransactionBatch(listOf(sig1, sig2))

        assertEquals(2, results.size)
        assertNull(results[sig1])
        assertNull(results[sig2])
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
    ) = transactionBatchResponse(transactionResultJson(sig, slot, mint, recipientWallet, preAmount, postAmount, decimals))

    // Wraps one or more transaction "result" JSON objects into a JSON-RPC batch array response.
    // "id"s are assigned 0, 1, ... matching the order getTransactionBatch sends them in its request.
    private fun transactionBatchResponse(vararg results: String) =
        results.mapIndexed { i, result -> """{"jsonrpc":"2.0","id":$i,"result":$result}""" }
            .joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun transactionResultJson(
        sig: String,
        slot: Long,
        mint: String,
        recipientWallet: String,
        @Suppress("UNUSED_PARAMETER") preAmount: Long,
        postAmount: Long,
        decimals: Int,
    ) = """
        {
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
    """.trimIndent()

    private fun twoSignaturesResponse(sig1: String, slot1: Long, sig2: String, slot2: Long) = """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": [
            {
              "signature": "$sig1",
              "slot": $slot1,
              "err": null,
              "blockTime": 1699000000
            },
            {
              "signature": "$sig2",
              "slot": $slot2,
              "err": null,
              "blockTime": 1699000000
            }
          ]
        }
    """.trimIndent()

    private fun signaturesResponseFor(vararg sigs: Pair<String, Long>) = buildString {
        append("""{"jsonrpc":"2.0","id":1,"result":[""")
        sigs.forEachIndexed { i, (sig, slot) ->
            if (i > 0) append(",")
            append("""{"signature":"$sig","slot":$slot,"err":null,"blockTime":1699000000}""")
        }
        append("]}")
    }

    private fun failedTransactionResponse(sig: String, slot: Long) =
        transactionBatchResponse(failedTransactionResultJson(sig, slot))

    private fun failedTransactionResultJson(sig: String, slot: Long) = """
        {
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
    """.trimIndent()
}
