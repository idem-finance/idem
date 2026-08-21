package finance.idem.infrastructure.chain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.reconciliation.ReorgReversalCommand
import finance.idem.application.reconciliation.ReorgReversalResult
import finance.idem.application.reconciliation.ReorgReversalUseCase
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.chain.ChainCheckpoint
import finance.idem.core.chain.ChainCheckpointRepository
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AlchemyWebhookServiceHandleTest {
    private lateinit var watchedAddressRepository: WatchedAddressRepository
    private lateinit var checkpointRepository: ChainCheckpointRepository
    private lateinit var postTransactionUseCase: PostTransactionUseCase
    private lateinit var deadLetterRecorder: DeadLetterRecorder
    private lateinit var reorgReversalUseCase: ReorgReversalUseCase
    private lateinit var settlementRepository: SettlementRepository
    private lateinit var service: AlchemyWebhookService

    private val signingKey = "test-webhook-signing-key"
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private val usdcContract = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val watchedWallet = "0xabcdef1234567890abcdef1234567890abcdef34"
    private val txHash = "0xabc123def456abc123def456abc123def456abc123def456abc123def456abc1"
    private val tenantId = "00000000-0000-0000-0000-000000000001"
    private val debitAccountId = "00000000-0000-0000-0000-000000000002"
    private val creditAccountId = "00000000-0000-0000-0000-000000000003"

    private val watchedAddress =
        WatchedAddress(
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
        deadLetterRecorder = mock()
        reorgReversalUseCase = mock()
        settlementRepository = mock()
        service =
            AlchemyWebhookService(
                watchedAddressRepository = watchedAddressRepository,
                chainCheckpointRepository = checkpointRepository,
                postTransactionUseCase = postTransactionUseCase,
                objectMapper = objectMapper,
                config = ChainConfig(alchemyWebhookSigningKey = signingKey),
                deadLetterRecorder = deadLetterRecorder,
                reorgReversalUseCase = reorgReversalUseCase,
                settlementRepository = settlementRepository,
            )
    }

    @Test
    fun `returns failure when X-Alchemy-Signature is missing and signing key is configured`() {
        val result = service.handle(null, buildPayload(txHash, watchedWallet, usdcContract))

        assertTrue(result.isFailure)
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `returns failure when X-Alchemy-Signature is wrong`() {
        val result = service.handle("deadbeef0000", buildPayload(txHash, watchedWallet, usdcContract))

        assertTrue(result.isFailure)
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `returns success when signing key is blank (dev mode — skips HMAC validation)`() {
        val devService =
            AlchemyWebhookService(
                watchedAddressRepository = watchedAddressRepository,
                chainCheckpointRepository = checkpointRepository,
                postTransactionUseCase = postTransactionUseCase,
                objectMapper = objectMapper,
                config = ChainConfig(alchemyWebhookSigningKey = ""),
                deadLetterRecorder = deadLetterRecorder,
                reorgReversalUseCase = reorgReversalUseCase,
                settlementRepository = settlementRepository,
            )
        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(emptyList())

        val result = devService.handle(null, buildPayload(txHash, watchedWallet, usdcContract))

        assertTrue(result.isSuccess)
    }

    @Test
    fun `returns success and ignores non-ADDRESS_ACTIVITY webhooks`() {
        val body = """{"type":"NFT_ACTIVITY","event":{"network":"ETH_MAINNET","activity":[]}}"""

        val result = service.handle(computeHmac(signingKey, body), body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `returns success and ignores unknown network`() {
        val body = """{"type":"ADDRESS_ACTIVITY","event":{"network":"UNKNOWN_CHAIN","activity":[]}}"""

        val result = service.handle(computeHmac(signingKey, body), body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `posts transaction and advances checkpoint for valid USDC transfer`() {
        val body = buildPayload(txHash, watchedWallet, usdcContract, rawValue = "0x000f4240")

        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(
            ChainCheckpoint("EVM_1", 19_000_000L, Instant.now()),
        )
        whenever(postTransactionUseCase.execute(any())).thenReturn(
            Result.success(TransactionId(UUID.randomUUID())),
        )

        val result = service.handle(computeHmac(signingKey, body), body)

        assertTrue(result.isSuccess)

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
    fun `delegates to DeadLetterRecorder when postTransactionUseCase fails`() {
        val body = buildPayload(txHash, watchedWallet, usdcContract, rawValue = "0x000f4240")
        val error = RuntimeException("conflict")

        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(
            ChainCheckpoint("EVM_1", 19_000_000L, Instant.now()),
        )
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.failure(error))

        val result = service.handle(computeHmac(signingKey, body), body)

        assertTrue(result.isSuccess)

        val transferCaptor = argumentCaptor<DetectedTransfer>()
        verify(deadLetterRecorder).record(transferCaptor.capture(), eq("EVM_1"), eq("alchemy-webhook"), eq(error), eq("Alchemy webhook"))
        assertEquals("EVM_1:$txHash:0", transferCaptor.firstValue.idempotencyKey)
    }

    @Test
    fun `skips activity where toAddress is not watched`() {
        val body = buildPayload(txHash, "0xffffffffffffffffffffffffffffffffffffffff", usdcContract)

        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(null)

        val result = service.handle(computeHmac(signingKey, body), body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `advances checkpoint to payload max block even when no activities match watched addresses`() {
        // Payload has an activity at block 0x12a05f2 (19_531_250) but toAddress is not watched.
        // Checkpoint should still advance so the fallback reader skips these blocks on recovery.
        val unmatchedBody =
            buildPayload(
                txHash = txHash,
                toAddress = "0xffffffffffffffffffffffffffffffffffffffff",
                contract = usdcContract,
                blockNum = "0x12a05f2", // 19_531_250
            )

        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(
            ChainCheckpoint("EVM_1", 19_000_000L, Instant.now()),
        )

        val result = service.handle(computeHmac(signingKey, unmatchedBody), unmatchedBody)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
        // Checkpoint must advance to the block in the payload even though nothing matched.
        verify(checkpointRepository).save("EVM_1", 19_531_250L)
    }

    // -- removed:true (reorg) handling --

    @Test
    fun `removed log for a watched address invokes ReorgReversalUseCase and never posts a new transaction`() {
        val body = buildPayload(txHash, watchedWallet, usdcContract, blockNum = "0x12a05f2", logIndex = "0x2", removed = true)
        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(ChainCheckpoint("EVM_1", 19_000_000L, Instant.now()))
        whenever(reorgReversalUseCase.execute(any())).thenReturn(Result.success(ReorgReversalResult.NoMatchingSettlement))

        val result = service.handle(computeHmac(signingKey, body), body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
        val cmdCaptor = argumentCaptor<ReorgReversalCommand>()
        verify(reorgReversalUseCase).execute(cmdCaptor.capture())
        val cmd = cmdCaptor.firstValue
        assertEquals(TenantId(UUID.fromString(tenantId)), cmd.tenantId)
        assertEquals(txHash, cmd.txHash)
        assertEquals(2, cmd.logIndex)
        assertEquals("EVM_1", cmd.chainKey)
    }

    @Test
    fun `removed log for an unwatched address does not invoke ReorgReversalUseCase`() {
        val body =
            buildPayload(
                txHash,
                "0xffffffffffffffffffffffffffffffffffffffff",
                usdcContract,
                removed = true,
            )
        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(null)

        val result = service.handle(computeHmac(signingKey, body), body)

        assertTrue(result.isSuccess)
        verify(reorgReversalUseCase, never()).execute(any())
    }

    @Test
    fun `ReorgReversalUseCase failure is logged and does not fail the webhook`() {
        val body = buildPayload(txHash, watchedWallet, usdcContract, removed = true)
        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(null)
        whenever(reorgReversalUseCase.execute(any())).thenReturn(Result.failure(RuntimeException("db down")))

        val result = service.handle(computeHmac(signingKey, body), body)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `removed log with unparseable logIndex aborts the fast-path reversal rather than guessing 0`() {
        val body = buildPayload(txHash, watchedWallet, usdcContract, logIndex = "not-hex", removed = true)
        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(null)

        val result = service.handle(computeHmac(signingKey, body), body)

        assertTrue(result.isSuccess)
        verify(reorgReversalUseCase, never()).execute(any())
    }

    @Test
    fun `new activity with unparseable logIndex is skipped rather than posted at a guessed index 0`() {
        val body = buildPayload(txHash, watchedWallet, usdcContract, rawValue = "0x000f4240", logIndex = "not-hex")
        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(ChainCheckpoint("EVM_1", 19_000_000L, Instant.now()))

        val result = service.handle(computeHmac(signingKey, body), body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
    }

    // -- stale replay of reorged-out evidence --

    @Test
    fun `a redelivery replaying the exact reorged-out evidence is dropped without posting`() {
        val body = buildPayload(txHash, watchedWallet, usdcContract, rawValue = "0x000f4240", blockNum = "0x12a05f2", logIndex = "0x0")
        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(ChainCheckpoint("EVM_1", 19_000_000L, Instant.now()))
        val reorgedSettlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = TenantId(UUID.fromString(tenantId)),
                accountId = AccountId(UUID.fromString(debitAccountId)),
                amount = MonetaryAmount.of("1.000000"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = watchedWallet,
                status = EntryStatus.REORGED,
                txHash = txHash,
                blockNumber = 19_531_250L, // same block as the redelivered activity (0x12a05f2)
                createdAt = Instant.now(),
                createdBy = "system",
            )
        whenever(settlementRepository.findReorgedByTxHashAndLogIndex(TenantId(UUID.fromString(tenantId)), txHash, 0))
            .thenReturn(reorgedSettlement)

        val result = service.handle(computeHmac(signingKey, body), body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `a genuine re-mine at a different block is posted normally despite a prior reorg on this key`() {
        val body = buildPayload(txHash, watchedWallet, usdcContract, rawValue = "0x000f4240", blockNum = "0x12a05f2", logIndex = "0x0")
        whenever(watchedAddressRepository.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(ChainCheckpoint("EVM_1", 19_000_000L, Instant.now()))
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId(UUID.randomUUID())))
        val reorgedSettlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = TenantId(UUID.fromString(tenantId)),
                accountId = AccountId(UUID.fromString(debitAccountId)),
                amount = MonetaryAmount.of("1.000000"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = watchedWallet,
                status = EntryStatus.REORGED,
                txHash = txHash,
                blockNumber = 1L, // a different (earlier, reorged-out) block than this redelivery's 19_531_250
                createdAt = Instant.now(),
                createdBy = "system",
            )
        whenever(settlementRepository.findReorgedByTxHashAndLogIndex(TenantId(UUID.fromString(tenantId)), txHash, 0))
            .thenReturn(reorgedSettlement)

        val result = service.handle(computeHmac(signingKey, body), body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase).execute(any())
    }

    private fun buildPayload(
        txHash: String,
        toAddress: String,
        contract: String,
        rawValue: String = "0x000f4240",
        blockNum: String = "0x12a05f2",
        logIndex: String = "0x0",
        network: String = "ETH_MAINNET",
        removed: Boolean = false,
    ): String =
        """
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
                  "removed": $removed
                }
              }
            ]
          }
        }
        """.trimIndent()

    private fun computeHmac(
        key: String,
        body: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
