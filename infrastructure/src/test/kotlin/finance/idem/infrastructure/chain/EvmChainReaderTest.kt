package finance.idem.infrastructure.chain

import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.web3j.protocol.Web3j
import java.math.BigDecimal

class EvmChainReaderTest {

    private val usdcContract = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
    private val watchedWallet = "0xabcdef1234567890abcdef1234567890abcdef34"
    private val tenantId = "tenant-1"

    private val watchedAddress = WatchedAddress(
        chainKey = "EVM_1",
        walletAddress = watchedWallet,
        tokenContract = usdcContract,
        token = StablecoinToken.USDC,
        tenantId = "tenant-1",
        debitAccountId = "debit-1",
        creditAccountId = "credit-1",
    )

    private val reader = EvmChainReader(
        chainKey = "EVM_1",
        web3j = mock<Web3j>(),
        watchedAddresses = listOf(watchedAddress),
    )

    private val transferTopic = EvmChainReader.TRANSFER_EVENT_TOPIC
    private val fromPadded = "0x000000000000000000000000abcdef1234567890abcdef1234567890abcdef12"
    private val toPadded = "0x000000000000000000000000abcdef1234567890abcdef1234567890abcdef34"
    private val oneUsdcData = "0x00000000000000000000000000000000000000000000000000000000000f4240"
    private val txHash = "0xabc123def456abc123def456abc123def456abc123def456abc123def456abc1"

    @Test
    fun `decodes ERC20 Transfer event to watched address`() {
        val result = reader.decodeTransfer(
            topics = listOf(transferTopic, fromPadded, toPadded),
            data = oneUsdcData,
            txHash = txHash,
            blockNumber = 19_000_000L,
            logIndex = 0,
            contractAddress = usdcContract,
        )

        assertEquals("EVM_1:$txHash:0", result!!.idempotencyKey)
        assertEquals(MonetaryAmount.of(BigDecimal("1.000000")), result.entry.amount)
        assertEquals(StablecoinToken.USDC, result.entry.token)
        assertEquals(ChainId.EVM, result.entry.chainId)
        assertEquals(txHash, result.entry.txHash)
        assertEquals(19_000_000L, result.entry.blockNumber)
        assertEquals(watchedWallet.lowercase(), result.entry.walletAddress)
        assertEquals(usdcContract, result.entry.tokenContract)
    }

    @Test
    fun `ignores log with wrong contract address`() {
        val result = reader.decodeTransfer(
            topics = listOf(transferTopic, fromPadded, toPadded),
            data = oneUsdcData,
            txHash = txHash,
            blockNumber = 19_000_000L,
            logIndex = 0,
            contractAddress = "0xdAC17F958D2ee523a2206206994597C13D831ec7", // USDT contract
        )

        assertNull(result)
    }

    @Test
    fun `ignores log to unwatched wallet address`() {
        val unwatchedPadded = "0x000000000000000000000000ffffffffffffffffffffffffffffffffffffffff"

        val result = reader.decodeTransfer(
            topics = listOf(transferTopic, fromPadded, unwatchedPadded),
            data = oneUsdcData,
            txHash = txHash,
            blockNumber = 19_000_000L,
            logIndex = 0,
            contractAddress = usdcContract,
        )

        assertNull(result)
    }

    @Test
    fun `ignores log with fewer than 3 topics`() {
        val result = reader.decodeTransfer(
            topics = listOf(transferTopic, fromPadded),
            data = oneUsdcData,
            txHash = txHash,
            blockNumber = 19_000_000L,
            logIndex = 0,
            contractAddress = usdcContract,
        )

        assertNull(result)
    }

    @Test
    fun `ignores log with wrong event signature in topic 0`() {
        val result = reader.decodeTransfer(
            topics = listOf("0x0000000000000000000000000000000000000000000000000000000000000000", fromPadded, toPadded),
            data = oneUsdcData,
            txHash = txHash,
            blockNumber = 19_000_000L,
            logIndex = 0,
            contractAddress = usdcContract,
        )

        assertNull(result)
    }

    @Test
    fun `log index in idempotency key prevents collision on multi-transfer tx`() {
        val result0 = reader.decodeTransfer(
            topics = listOf(transferTopic, fromPadded, toPadded),
            data = oneUsdcData,
            txHash = txHash,
            blockNumber = 19_000_000L,
            logIndex = 0,
            contractAddress = usdcContract,
        )
        val result5 = reader.decodeTransfer(
            topics = listOf(transferTopic, fromPadded, toPadded),
            data = oneUsdcData,
            txHash = txHash,
            blockNumber = 19_000_000L,
            logIndex = 5,
            contractAddress = usdcContract,
        )

        assertEquals("EVM_1:$txHash:0", result0!!.idempotencyKey)
        assertEquals("EVM_1:$txHash:5", result5!!.idempotencyKey)
    }

    @Test
    fun `contract address comparison is case-insensitive`() {
        val result = reader.decodeTransfer(
            topics = listOf(transferTopic, fromPadded, toPadded),
            data = oneUsdcData,
            txHash = txHash,
            blockNumber = 19_000_000L,
            logIndex = 0,
            contractAddress = usdcContract.lowercase(),
        )

        assertEquals("EVM_1:$txHash:0", result!!.idempotencyKey)
    }

    @Test
    fun `poll returns empty list when no watched addresses match chain key`() {
        val readerForOtherChain = EvmChainReader(
            chainKey = "EVM_8453",
            web3j = mock<Web3j>(),
            watchedAddresses = listOf(watchedAddress), // only has EVM_1
        )

        val result = readerForOtherChain.poll(0L)

        assertEquals(emptyList<DetectedTransfer>(), result)
    }

    @Test
    fun `decodes USDT Transfer with 6 decimal precision`() {
        val usdtContract = "0xdAC17F958D2ee523a2206206994597C13D831ec7"
        val usdtReader = EvmChainReader(
            chainKey = "EVM_1",
            web3j = mock<Web3j>(),
            watchedAddresses = listOf(
                WatchedAddress(
                    chainKey = "EVM_1",
                    walletAddress = watchedWallet,
                    tokenContract = usdtContract,
                    token = StablecoinToken.USDT,
                    tenantId = tenantId,
                    debitAccountId = "debit-1",
                    creditAccountId = "credit-1",
                )
            ),
        )

        val result = usdtReader.decodeTransfer(
            topics = listOf(transferTopic, fromPadded, toPadded),
            data = oneUsdcData,
            txHash = txHash,
            blockNumber = 19_000_000L,
            logIndex = 0,
            contractAddress = usdtContract,
        )

        assertEquals(StablecoinToken.USDT, result!!.entry.token)
        assertEquals(MonetaryAmount.of(BigDecimal("1.000000")), result.entry.amount)
    }

    @Test
    fun `decodes PYUSD Transfer with 6 decimal precision`() {
        val pyusdContract = "0x6c3ea9036406852006290770BEdFcAbA0e23A0e8"
        val pyusdReader = EvmChainReader(
            chainKey = "EVM_1",
            web3j = mock<Web3j>(),
            watchedAddresses = listOf(
                WatchedAddress(
                    chainKey = "EVM_1",
                    walletAddress = watchedWallet,
                    tokenContract = pyusdContract,
                    token = StablecoinToken.PYUSD,
                    tenantId = tenantId,
                    debitAccountId = "debit-1",
                    creditAccountId = "credit-1",
                )
            ),
        )

        val result = pyusdReader.decodeTransfer(
            topics = listOf(transferTopic, fromPadded, toPadded),
            data = oneUsdcData,
            txHash = txHash,
            blockNumber = 19_000_000L,
            logIndex = 0,
            contractAddress = pyusdContract,
        )

        assertEquals(StablecoinToken.PYUSD, result!!.entry.token)
        assertEquals(MonetaryAmount.of(BigDecimal("1.000000")), result.entry.amount)
    }

    @Test
    fun `decodes BRZ Transfer with 18 decimal precision`() {
        val brzContract = "0x491604c0FDF08347Dd1fa4Ee062a822A5DD06B5D"
        val oneEther = "0x0000000000000000000000000000000000000000000000000de0b6b3a7640000" // 1e18
        val brzReader = EvmChainReader(
            chainKey = "EVM_1",
            web3j = mock<Web3j>(),
            watchedAddresses = listOf(
                WatchedAddress(
                    chainKey = "EVM_1",
                    walletAddress = watchedWallet,
                    tokenContract = brzContract,
                    token = StablecoinToken.BRZ,
                    tenantId = tenantId,
                    debitAccountId = "debit-1",
                    creditAccountId = "credit-1",
                )
            ),
        )

        val result = brzReader.decodeTransfer(
            topics = listOf(transferTopic, fromPadded, toPadded),
            data = oneEther,
            txHash = txHash,
            blockNumber = 19_000_000L,
            logIndex = 0,
            contractAddress = brzContract,
        )

        assertEquals(StablecoinToken.BRZ, result!!.entry.token)
        assertEquals(MonetaryAmount.of(BigDecimal("1.000000000000000000")), result.entry.amount)
    }
}
