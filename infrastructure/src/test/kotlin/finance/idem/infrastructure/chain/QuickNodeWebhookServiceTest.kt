package finance.idem.infrastructure.chain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TransactionId
import finance.idem.core.chain.ChainCheckpoint
import finance.idem.core.chain.ChainCheckpointRepository
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class QuickNodeWebhookServiceTest {

    private lateinit var watchedAddressRepository: WatchedAddressRepository
    private lateinit var checkpointRepository: ChainCheckpointRepository
    private lateinit var postTransactionUseCase: PostTransactionUseCase
    private lateinit var deadLetterRecorder: DeadLetterRecorder
    private lateinit var solanaReader: SolanaChainReader
    private lateinit var service: QuickNodeWebhookService

    private val signingKey = "test-webhook-signing-key"
    private val testNonce = "test-nonce-123"
    private val testTimestamp = "1718000000"
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private val testSignature = "5UfgJ5vHh9KKmSE38wLvSQvTestSignatureAAAAAAAA"
    private val testSlot = 154_628_853L
    private val tenantId = "00000000-0000-0000-0000-000000000001"
    private val debitAccountId = "00000000-0000-0000-0000-000000000002"
    private val creditAccountId = "00000000-0000-0000-0000-000000000003"
    private val watchedWallet = "HN7cABqLq46Es1jh92dQQisAi18upoBB6aWCCCqSBKpJ"
    private val usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    private val watchedAddress = WatchedAddress(
        chainKey = "SOLANA",
        walletAddress = watchedWallet,
        tokenContract = usdcMint,
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
        solanaReader = mock()
        service = QuickNodeWebhookService(
            watchedAddressRepository = watchedAddressRepository,
            chainCheckpointRepository = checkpointRepository,
            postTransactionUseCase = postTransactionUseCase,
            objectMapper = objectMapper,
            config = ChainConfig(quicknodeWebhookSecret = signingKey),
            deadLetterRecorder = deadLetterRecorder,
            chainReaders = listOf(solanaReader),
        )
    }

    // -- HMAC validation --

    @Test
    fun `returns failure when X-QN-Signature is missing and secret is configured`() {
        val result = service.handle(null, testNonce, testTimestamp, buildBody(testSignature, testSlot))

        assertTrue(result.isFailure)
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `returns failure when X-QN-Signature is wrong`() {
        val result = service.handle("deadbeef0000", testNonce, testTimestamp, buildBody(testSignature, testSlot))

        assertTrue(result.isFailure)
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `returns failure when X-QN-Nonce is missing and secret is configured`() {
        val body = buildBody(testSignature, testSlot)
        val result = service.handle(computeHmac(signingKey, testNonce, testTimestamp, body), null, testTimestamp, body)

        assertTrue(result.isFailure)
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `returns failure when X-QN-Timestamp is missing and secret is configured`() {
        val body = buildBody(testSignature, testSlot)
        val result = service.handle(computeHmac(signingKey, testNonce, testTimestamp, body), testNonce, null, body)

        assertTrue(result.isFailure)
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `returns failure when signature was computed over body alone (legacy scheme)`() {
        val body = buildBody(testSignature, testSlot)
        val legacySignature = computeHmac(signingKey, "", "", body)

        val result = service.handle(legacySignature, testNonce, testTimestamp, body)

        assertTrue(result.isFailure)
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `returns success when secret is blank (dev mode — skips HMAC validation)`() {
        val devService = QuickNodeWebhookService(
            watchedAddressRepository = watchedAddressRepository,
            chainCheckpointRepository = checkpointRepository,
            postTransactionUseCase = postTransactionUseCase,
            objectMapper = objectMapper,
            config = ChainConfig(quicknodeWebhookSecret = ""),
            deadLetterRecorder = deadLetterRecorder,
            chainReaders = listOf(solanaReader),
        )
        whenever(watchedAddressRepository.findByChainKey("SOLANA")).thenReturn(emptyList())

        val result = devService.handle(null, null, null, buildBody(testSignature, testSlot))

        assertTrue(result.isSuccess)
    }

    // -- payload parsing --

    @Test
    fun `returns success and does not process when body is not valid JSON`() {
        val body = "not-json"

        val result = service.handle(computeHmac(signingKey, testNonce, testTimestamp, body), testNonce, testTimestamp, body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
        verify(checkpointRepository, never()).save(any(), any())
    }

    @Test
    fun `returns success and does not process legacy bare-array body (no data envelope)`() {
        val body = """[{"signature":"$testSignature","slot":$testSlot,"network":"mainnet-beta"}]"""

        val result = service.handle(computeHmac(signingKey, testNonce, testTimestamp, body), testNonce, testTimestamp, body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
        verify(checkpointRepository, never()).save(any(), any())
    }

    @Test
    fun `returns success and processes nothing when data array is empty`() {
        val body = """{"data":[],"metadata":{"streamId":"st_test","dataset":"block"}}"""

        val result = service.handle(computeHmac(signingKey, testNonce, testTimestamp, body), testNonce, testTimestamp, body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
        verify(checkpointRepository, never()).save(any(), any())
    }

    // -- network routing --

    @Test
    fun `returns success and ignores unknown network`() {
        val body = """{"data":[{"signature":"$testSignature","slot":$testSlot,"network":"devnet"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""

        val result = service.handle(computeHmac(signingKey, testNonce, testTimestamp, body), testNonce, testTimestamp, body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
    }

    // -- reader availability --

    @Test
    fun `returns success and skips payload without advancing checkpoint when no SolanaChainReader is configured`() {
        val noReaderService = QuickNodeWebhookService(
            watchedAddressRepository = watchedAddressRepository,
            chainCheckpointRepository = checkpointRepository,
            postTransactionUseCase = postTransactionUseCase,
            objectMapper = objectMapper,
            config = ChainConfig(quicknodeWebhookSecret = signingKey),
            deadLetterRecorder = deadLetterRecorder,
            chainReaders = emptyList(),
        )
        val body = buildBody(testSignature, testSlot)

        val result = noReaderService.handle(computeHmac(signingKey, testNonce, testTimestamp, body), testNonce, testTimestamp, body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
        verify(checkpointRepository, never()).save(any(), any())
    }

    // -- transfer processing --

    @Test
    fun `posts transaction and advances checkpoint for valid USDC transfer`() {
        val body = buildBody(testSignature, testSlot)
        val tx = SolanaChainReader.SolanaTransactionResult()
        val transfer = buildTransfer()

        whenever(watchedAddressRepository.findByChainKey("SOLANA")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("SOLANA")).thenReturn(null)
        whenever(solanaReader.getTransaction(testSignature)).thenReturn(tx)
        whenever(solanaReader.decodeTransfer(tx, testSignature, testSlot, watchedAddress)).thenReturn(transfer)
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId(UUID.randomUUID())))

        val result = service.handle(computeHmac(signingKey, testNonce, testTimestamp, body), testNonce, testTimestamp, body)

        assertTrue(result.isSuccess)

        val cmdCaptor = argumentCaptor<PostTransactionCommand>()
        verify(postTransactionUseCase).execute(cmdCaptor.capture())
        val cmd = cmdCaptor.firstValue
        assertEquals("SOLANA:$testSignature:2", cmd.idempotencyKey)
        assertEquals(tenantId, cmd.tenantId.value.toString())
        assertEquals("quicknode-webhook", cmd.createdBy)
        assertEquals(2, cmd.lines.size)

        verify(checkpointRepository).save("SOLANA", testSlot)
    }

    @Test
    fun `delegates to DeadLetterRecorder when postTransactionUseCase fails`() {
        val body = buildBody(testSignature, testSlot)
        val tx = SolanaChainReader.SolanaTransactionResult()
        val transfer = buildTransfer()
        val error = RuntimeException("conflict")

        whenever(watchedAddressRepository.findByChainKey("SOLANA")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("SOLANA")).thenReturn(null)
        whenever(solanaReader.getTransaction(testSignature)).thenReturn(tx)
        whenever(solanaReader.decodeTransfer(tx, testSignature, testSlot, watchedAddress)).thenReturn(transfer)
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.failure(error))

        val result = service.handle(computeHmac(signingKey, testNonce, testTimestamp, body), testNonce, testTimestamp, body)

        assertTrue(result.isSuccess)

        verify(deadLetterRecorder).record(eq(transfer), eq("SOLANA"), eq("quicknode-webhook"), eq(error), eq("QuickNode webhook"))
    }

    @Test
    fun `advances checkpoint even when no watched addresses configured`() {
        val body = buildBody(testSignature, testSlot)
        whenever(watchedAddressRepository.findByChainKey("SOLANA")).thenReturn(emptyList())
        whenever(checkpointRepository.findByChainKey("SOLANA")).thenReturn(null)

        val result = service.handle(computeHmac(signingKey, testNonce, testTimestamp, body), testNonce, testTimestamp, body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
        verify(checkpointRepository).save("SOLANA", testSlot)
    }

    @Test
    fun `advances checkpoint even when getTransaction returns null`() {
        val body = buildBody(testSignature, testSlot)
        whenever(watchedAddressRepository.findByChainKey("SOLANA")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("SOLANA")).thenReturn(null)
        whenever(solanaReader.getTransaction(testSignature)).thenReturn(null)

        val result = service.handle(computeHmac(signingKey, testNonce, testTimestamp, body), testNonce, testTimestamp, body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
        verify(checkpointRepository).save("SOLANA", testSlot)
    }

    @Test
    fun `advances checkpoint even when decodeTransfer returns null`() {
        val body = buildBody(testSignature, testSlot)
        val tx = SolanaChainReader.SolanaTransactionResult()
        whenever(watchedAddressRepository.findByChainKey("SOLANA")).thenReturn(listOf(watchedAddress))
        whenever(checkpointRepository.findByChainKey("SOLANA")).thenReturn(null)
        whenever(solanaReader.getTransaction(testSignature)).thenReturn(tx)
        whenever(solanaReader.decodeTransfer(any(), any(), any(), any())).thenReturn(null)

        val result = service.handle(computeHmac(signingKey, testNonce, testTimestamp, body), testNonce, testTimestamp, body)

        assertTrue(result.isSuccess)
        verify(postTransactionUseCase, never()).execute(any())
        verify(checkpointRepository).save("SOLANA", testSlot)
    }

    @Test
    fun `does not save checkpoint when new slot is not greater than existing`() {
        val body = buildBody(testSignature, testSlot)
        whenever(watchedAddressRepository.findByChainKey("SOLANA")).thenReturn(emptyList())
        whenever(checkpointRepository.findByChainKey("SOLANA")).thenReturn(
            ChainCheckpoint("SOLANA", testSlot, Instant.now())
        )

        service.handle(computeHmac(signingKey, testNonce, testTimestamp, body), testNonce, testTimestamp, body)

        verify(checkpointRepository, never()).save(any(), any())
    }

    // -- companion object unit tests --

    @Test
    fun `isValidSignature returns true for correct HMAC`() {
        val key = "secret"
        val body = """{"data":[{"signature":"abc","slot":1,"network":"mainnet-beta"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""
        val sig = computeHmac(key, testNonce, testTimestamp, body)
        assertTrue(QuickNodeWebhookService.isValidSignature(key, testNonce, testTimestamp, body, sig))
    }

    @Test
    fun `isValidSignature returns false for wrong signature`() {
        val key = "secret"
        val body = """{"data":[{"signature":"abc","slot":1,"network":"mainnet-beta"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""
        assertFalse(QuickNodeWebhookService.isValidSignature(key, testNonce, testTimestamp, body, "deadbeef"))
    }

    @Test
    fun `isValidSignature returns false when key differs`() {
        val body = """{"data":[{"signature":"abc","slot":1,"network":"mainnet-beta"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""
        val sig = computeHmac("correct-key", testNonce, testTimestamp, body)
        assertFalse(QuickNodeWebhookService.isValidSignature("wrong-key", testNonce, testTimestamp, body, sig))
    }

    @Test
    fun `isValidSignature returns false when nonce differs`() {
        val key = "secret"
        val body = """{"data":[{"signature":"abc","slot":1,"network":"mainnet-beta"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""
        val sig = computeHmac(key, "nonce-a", testTimestamp, body)
        assertFalse(QuickNodeWebhookService.isValidSignature(key, "nonce-b", testTimestamp, body, sig))
    }

    @Test
    fun `isValidSignature returns false when timestamp differs`() {
        val key = "secret"
        val body = """{"data":[{"signature":"abc","slot":1,"network":"mainnet-beta"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""
        val sig = computeHmac(key, testNonce, "1718000000", body)
        assertFalse(QuickNodeWebhookService.isValidSignature(key, testNonce, "1718000099", body, sig))
    }

    @Test
    fun `networkToChainKey maps mainnet-beta to SOLANA`() {
        assertEquals("SOLANA", QuickNodeWebhookService.networkToChainKey("mainnet-beta"))
    }

    @Test
    fun `networkToChainKey returns null for unknown network`() {
        assertNull(QuickNodeWebhookService.networkToChainKey("devnet"))
    }

    @Test
    fun `networkToChainKey is case-insensitive`() {
        assertEquals("SOLANA", QuickNodeWebhookService.networkToChainKey("MAINNET-BETA"))
    }

    // -- helpers --

    private fun buildBody(signature: String, slot: Long, network: String = "mainnet-beta"): String =
        """{"data":[{"signature":"$signature","slot":$slot,"network":"$network"}],"metadata":{"streamId":"st_test","dataset":"block"}}"""

    private fun buildTransfer() = DetectedTransfer(
        idempotencyKey = "SOLANA:$testSignature:2",
        entry = OnChainEntry(
            amount = MonetaryAmount.of(BigDecimal("100.000000")),
            token = StablecoinToken.USDC,
            chainId = ChainId.SOLANA,
            txHash = testSignature,
            blockNumber = testSlot,
            walletAddress = watchedWallet,
            tokenContract = usdcMint,
        ),
        watchedAddress = watchedAddress,
    )

    private fun computeHmac(key: String, nonce: String, timestamp: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal((nonce + timestamp + body).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
