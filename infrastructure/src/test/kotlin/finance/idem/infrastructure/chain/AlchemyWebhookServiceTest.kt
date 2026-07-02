package finance.idem.infrastructure.chain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.chain.ChainCheckpointRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.math.BigDecimal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AlchemyWebhookServiceTest {
    private val mockWatchedRepo = mock<WatchedAddressRepository>()
    private val mockCheckpointRepo = mock<ChainCheckpointRepository>()
    private val mockUseCase = mock<PostTransactionUseCase>()
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private val service =
        AlchemyWebhookService(
            watchedAddressRepository = mockWatchedRepo,
            chainCheckpointRepository = mockCheckpointRepo,
            postTransactionUseCase = mockUseCase,
            objectMapper = objectMapper,
            config = ChainConfig(),
            deadLetterRecorder = mock<DeadLetterRecorder>(),
        )

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

    // -- HMAC validation --

    @Test
    fun `isValidSignature returns true for correct HMAC`() {
        val key = "test-signing-key"
        val body = """{"type":"ADDRESS_ACTIVITY"}"""
        val signature = computeHmac(key, body)

        assertTrue(AlchemyWebhookService.isValidSignature(key, body, signature))
    }

    @Test
    fun `isValidSignature returns false for wrong signature`() {
        val key = "test-signing-key"
        val body = """{"type":"ADDRESS_ACTIVITY"}"""

        assertFalse(AlchemyWebhookService.isValidSignature(key, body, "deadbeef"))
    }

    @Test
    fun `isValidSignature returns false when key differs`() {
        val body = """{"type":"ADDRESS_ACTIVITY"}"""
        val signature = computeHmac("correct-key", body)

        assertFalse(AlchemyWebhookService.isValidSignature("wrong-key", body, signature))
    }

    // -- networkToChainKey --

    @Test
    fun `networkToChainKey maps ETH_MAINNET to EVM_1`() {
        assertEquals("EVM_1", AlchemyWebhookService.networkToChainKey("ETH_MAINNET"))
    }

    @Test
    fun `networkToChainKey maps BASE_MAINNET to EVM_8453`() {
        assertEquals("EVM_8453", AlchemyWebhookService.networkToChainKey("BASE_MAINNET"))
    }

    @Test
    fun `networkToChainKey maps MATIC_MAINNET to EVM_137`() {
        assertEquals("EVM_137", AlchemyWebhookService.networkToChainKey("MATIC_MAINNET"))
    }

    @Test
    fun `networkToChainKey maps ETH_SEPOLIA to EVM_11155111`() {
        assertEquals("EVM_11155111", AlchemyWebhookService.networkToChainKey("ETH_SEPOLIA"))
    }

    @Test
    fun `networkToChainKey maps BASE_SEPOLIA to EVM_84532`() {
        assertEquals("EVM_84532", AlchemyWebhookService.networkToChainKey("BASE_SEPOLIA"))
    }

    @Test
    fun `networkToChainKey returns null for unknown network`() {
        assertNull(AlchemyWebhookService.networkToChainKey("UNKNOWN_CHAIN"))
    }

    @Test
    fun `networkToChainKey is case-insensitive`() {
        assertEquals("EVM_1", AlchemyWebhookService.networkToChainKey("eth_mainnet"))
    }

    // -- decodeActivity --

    @Test
    fun `decodeActivity returns DetectedTransfer for valid USDC activity`() {
        val activity =
            buildActivity(
                toAddress = watchedWallet,
                contract = usdcContract,
                rawValue = "0x000f4240", // 1_000_000 = 1 USDC (6 decimals)
                blockNum = "0x12a05f2", // 19_531_250
                logIndex = "0x0",
            )

        val result = service.decodeActivity(activity, "EVM_1", listOf(watchedAddress))

        assertNotNull(result)
        assertEquals("EVM_1:$txHash:0", result!!.idempotencyKey)
        assertEquals(MonetaryAmount.of(BigDecimal("1.000000")), result.entry.amount)
        assertEquals(StablecoinToken.USDC, result.entry.token)
        assertEquals(ChainId.EVM, result.entry.chainId)
        assertEquals(txHash, result.entry.txHash)
        assertEquals(19_531_250L, result.entry.blockNumber)
        assertEquals(watchedWallet.lowercase(), result.entry.walletAddress)
        assertEquals(usdcContract.lowercase(), result.entry.tokenContract)
        assertEquals("0xfrom", result.entry.fromAddress)
        assertEquals(watchedAddress, result.watchedAddress)
    }

    @Test
    fun `decodeActivity uses log logIndex in idempotency key`() {
        val activity =
            buildActivity(
                toAddress = watchedWallet,
                contract = usdcContract,
                rawValue = "0x000f4240",
                logIndex = "0x3",
            )

        val result = service.decodeActivity(activity, "EVM_1", listOf(watchedAddress))

        assertNotNull(result)
        assertEquals("EVM_1:$txHash:3", result!!.idempotencyKey)
    }

    @Test
    fun `decodeActivity returns null when category is not token`() {
        val activity =
            buildActivity(
                toAddress = watchedWallet,
                contract = usdcContract,
                rawValue = "0x000f4240",
                category = "external",
            )

        assertNull(service.decodeActivity(activity, "EVM_1", listOf(watchedAddress)))
    }

    @Test
    fun `decodeActivity returns null for reorged log (removed = true)`() {
        val activity =
            buildActivity(
                toAddress = watchedWallet,
                contract = usdcContract,
                rawValue = "0x000f4240",
                removed = true,
            )

        assertNull(service.decodeActivity(activity, "EVM_1", listOf(watchedAddress)))
    }

    @Test
    fun `decodeActivity returns null when toAddress is not watched`() {
        val activity =
            buildActivity(
                toAddress = "0xffffffffffffffffffffffffffffffffffffffff",
                contract = usdcContract,
                rawValue = "0x000f4240",
            )

        assertNull(service.decodeActivity(activity, "EVM_1", listOf(watchedAddress)))
    }

    @Test
    fun `decodeActivity returns null when contract is not watched`() {
        val activity =
            buildActivity(
                toAddress = watchedWallet,
                contract = "0x0000000000000000000000000000000000000000",
                rawValue = "0x000f4240",
            )

        assertNull(service.decodeActivity(activity, "EVM_1", listOf(watchedAddress)))
    }

    @Test
    fun `decodeActivity returns null when rawContract is null`() {
        val activity =
            AlchemyActivity(
                hash = txHash,
                toAddress = watchedWallet,
                fromAddress = "0xfrom",
                blockNum = "0x1",
                category = "token",
                rawContract = null,
            )

        assertNull(service.decodeActivity(activity, "EVM_1", listOf(watchedAddress)))
    }

    @Test
    fun `decodeActivity returns null when rawValue is blank`() {
        val activity =
            buildActivity(
                toAddress = watchedWallet,
                contract = usdcContract,
                rawValue = "0x",
            )

        assertNull(service.decodeActivity(activity, "EVM_1", listOf(watchedAddress)))
    }

    @Test
    fun `decodeActivity returns null when rawValue is unparseable`() {
        val activity =
            buildActivity(
                toAddress = watchedWallet,
                contract = usdcContract,
                rawValue = "not-hex",
            )

        assertNull(service.decodeActivity(activity, "EVM_1", listOf(watchedAddress)))
    }

    @Test
    fun `decodeActivity returns null when amount is zero`() {
        val activity =
            buildActivity(
                toAddress = watchedWallet,
                contract = usdcContract,
                rawValue = "0x0",
            )

        assertNull(service.decodeActivity(activity, "EVM_1", listOf(watchedAddress)))
    }

    @Test
    fun `decodeActivity address matching is case-insensitive`() {
        val activity =
            buildActivity(
                toAddress = watchedWallet.uppercase(),
                contract = usdcContract.uppercase(),
                rawValue = "0x000f4240",
            )

        val result = service.decodeActivity(activity, "EVM_1", listOf(watchedAddress))

        assertNotNull(result)
        assertEquals(watchedWallet.lowercase(), result!!.entry.walletAddress)
        assertEquals(usdcContract.lowercase(), result.entry.tokenContract)
    }

    @Test
    fun `decodeActivity logIndex defaults to 0 when log is absent`() {
        val activity =
            buildActivity(
                toAddress = watchedWallet,
                contract = usdcContract,
                rawValue = "0x000f4240",
                logIndex = null,
            )

        val result = service.decodeActivity(activity, "EVM_1", listOf(watchedAddress))

        assertNotNull(result)
        assertEquals("EVM_1:$txHash:0", result!!.idempotencyKey)
    }

    // -- helper --

    private fun buildActivity(
        toAddress: String,
        contract: String,
        rawValue: String,
        blockNum: String = "0x1",
        logIndex: String? = "0x0",
        category: String = "token",
        removed: Boolean = false,
    ): AlchemyActivity =
        AlchemyActivity(
            hash = txHash,
            fromAddress = "0xfrom",
            toAddress = toAddress,
            blockNum = blockNum,
            category = category,
            rawContract = AlchemyRawContract(rawValue = rawValue, address = contract, decimals = 6),
            log =
                AlchemyLog(logIndex = logIndex ?: "0x0", removed = removed).let {
                    if (logIndex == null) null else it
                },
        )

    private fun computeHmac(
        key: String,
        body: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
