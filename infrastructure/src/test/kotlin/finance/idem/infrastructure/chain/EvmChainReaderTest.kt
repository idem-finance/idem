package finance.idem.infrastructure.chain

import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.protocol.core.methods.response.EthBlockNumber
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt
import org.web3j.protocol.core.methods.response.Log
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Optional

class EvmChainReaderTest {
    private val usdcContract = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
    private val watchedWallet = "0xabcdef1234567890abcdef1234567890abcdef34"
    private val tenantId = "a1b2c3d4-0000-0000-0000-000000000001"

    private val watchedAddress =
        WatchedAddress(
            chainKey = "EVM_1",
            walletAddress = watchedWallet,
            tokenContract = usdcContract,
            token = StablecoinToken.USDC,
            tenantId = tenantId,
            debitAccountId = "a1b2c3d4-0000-0000-0000-000000000002",
            creditAccountId = "a1b2c3d4-0000-0000-0000-000000000003",
        )

    private val mockRepo = mock<WatchedAddressRepository>()
    private val reader = EvmChainReader("EVM_1", mock<Web3j>(), mockRepo)

    private val transferTopic = EvmChainReader.TRANSFER_EVENT_TOPIC
    private val fromPadded = "0x000000000000000000000000abcdef1234567890abcdef1234567890abcdef12"
    private val toPadded = "0x000000000000000000000000abcdef1234567890abcdef1234567890abcdef34"
    private val oneUsdcData = "0x00000000000000000000000000000000000000000000000000000000000f4240"
    private val txHash = "0xabc123def456abc123def456abc123def456abc123def456abc123def456abc1"

    @BeforeEach
    fun setUp() {
        whenever(mockRepo.findByChainKey("EVM_1")).thenReturn(listOf(watchedAddress))
    }

    @Test
    fun `decodes ERC20 Transfer event to watched address`() {
        val result =
            reader.decodeTransfer(
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
        assertEquals(usdcContract.lowercase(), result.entry.tokenContract)
        assertEquals("0x" + fromPadded.takeLast(40), result.entry.fromAddress)
        assertEquals(watchedAddress, result.watchedAddress)
    }

    @Test
    fun `normalizes contractAddress to lowercase in OnChainEntry`() {
        val result =
            reader.decodeTransfer(
                topics = listOf(transferTopic, fromPadded, toPadded),
                data = oneUsdcData,
                txHash = txHash,
                blockNumber = 19_000_000L,
                logIndex = 0,
                contractAddress = usdcContract.uppercase(),
            )

        assertEquals(usdcContract.lowercase(), result!!.entry.tokenContract)
    }

    @Test
    fun `ignores log with wrong contract address`() {
        val result =
            reader.decodeTransfer(
                topics = listOf(transferTopic, fromPadded, toPadded),
                data = oneUsdcData,
                txHash = txHash,
                blockNumber = 19_000_000L,
                logIndex = 0,
                contractAddress = "0xdAC17F958D2ee523a2206206994597C13D831ec7",
            )

        assertNull(result)
    }

    @Test
    fun `ignores log to unwatched wallet address`() {
        val unwatchedPadded = "0x000000000000000000000000ffffffffffffffffffffffffffffffffffffffff"

        val result =
            reader.decodeTransfer(
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
        val result =
            reader.decodeTransfer(
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
        val result =
            reader.decodeTransfer(
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
        val result0 =
            reader.decodeTransfer(
                topics = listOf(transferTopic, fromPadded, toPadded),
                data = oneUsdcData,
                txHash = txHash,
                blockNumber = 19_000_000L,
                logIndex = 0,
                contractAddress = usdcContract,
            )
        val result5 =
            reader.decodeTransfer(
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
        val result =
            reader.decodeTransfer(
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
    fun `poll returns empty list when repository returns no addresses for chain key`() {
        val emptyRepo = mock<WatchedAddressRepository>()
        whenever(emptyRepo.findByChainKey("EVM_8453")).thenReturn(emptyList())
        val readerForOtherChain = EvmChainReader("EVM_8453", mock<Web3j>(), emptyRepo)

        val result = readerForOtherChain.poll(0L)

        assertEquals(emptyList<DetectedTransfer>(), result)
    }

    // ── resolveScanBound ──────────────────────────────────────────────────────

    private fun mockFinalizedBlock(
        web3j: Web3j,
        number: Long?,
        hasError: Boolean = false,
    ) {
        val request = mock<Request<*, EthBlock>>()
        val ethBlock = mock<EthBlock>()
        whenever(web3j.ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false)).thenReturn(request)
        whenever(request.send()).thenReturn(ethBlock)
        whenever(ethBlock.hasError()).thenReturn(hasError)
        if (!hasError && number != null) {
            val block = mock<EthBlock.Block>()
            whenever(block.number).thenReturn(BigInteger.valueOf(number))
            whenever(ethBlock.block).thenReturn(block)
        } else {
            whenever(ethBlock.block).thenReturn(null)
        }
    }

    private fun mockLatestBlockNumber(
        web3j: Web3j,
        number: Long,
    ) {
        val request = mock<Request<*, EthBlockNumber>>()
        val response = mock<EthBlockNumber>()
        whenever(web3j.ethBlockNumber()).thenReturn(request)
        whenever(request.send()).thenReturn(response)
        whenever(response.blockNumber).thenReturn(BigInteger.valueOf(number))
    }

    @Test
    fun `resolveScanBound prefers the finalized tag when available`() {
        val web3j = mock<Web3j>()
        mockFinalizedBlock(web3j, number = 21_000_000L)
        val reader = EvmChainReader("EVM_1", web3j, mockRepo)

        val bound = reader.resolveScanBound()

        assertEquals(21_000_000L, bound.blockNumber)
        assertEquals(ConfirmationSource.FINALIZED_TAG, bound.source)
        assertEquals(null, bound.confirmationsUsed)
        verify(web3j, never()).ethBlockNumber()
    }

    @Test
    fun `resolveScanBound falls back to depth heuristic when finalized tag errors`() {
        val web3j = mock<Web3j>()
        mockFinalizedBlock(web3j, number = null, hasError = true)
        mockLatestBlockNumber(web3j, number = 21_000_012L)
        val reader = EvmChainReader("EVM_1", web3j, mockRepo, useFinalizedTag = true, confirmations = 12L)

        val bound = reader.resolveScanBound()

        assertEquals(21_000_000L, bound.blockNumber)
        assertEquals(ConfirmationSource.BLOCK_DEPTH_HEURISTIC, bound.source)
        assertEquals(12L, bound.confirmationsUsed)
    }

    @Test
    fun `resolveScanBound falls back to depth heuristic when finalized tag call throws`() {
        val web3j = mock<Web3j>()
        val request = mock<Request<*, EthBlock>>()
        whenever(web3j.ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false)).thenReturn(request)
        whenever(request.send()).thenThrow(IOException("method eth_getBlockByNumber('finalized') not supported"))
        mockLatestBlockNumber(web3j, number = 21_000_012L)
        val reader = EvmChainReader("EVM_1", web3j, mockRepo, useFinalizedTag = true, confirmations = 12L)

        val bound = reader.resolveScanBound()

        assertEquals(21_000_000L, bound.blockNumber)
    }

    @Test
    fun `resolveScanBound never calls the finalized tag when useFinalizedTag is false`() {
        val web3j = mock<Web3j>()
        mockLatestBlockNumber(web3j, number = 100L)
        val reader = EvmChainReader("EVM_1", web3j, mockRepo, useFinalizedTag = false, confirmations = 12L)

        val bound = reader.resolveScanBound()

        assertEquals(88L, bound.blockNumber)
        assertEquals(ConfirmationSource.BLOCK_DEPTH_HEURISTIC, bound.source)
        verify(web3j, never()).ethGetBlockByNumber(any(), any())
    }

    @Test
    fun `resolveScanBound with confirmations=0 and useFinalizedTag=false preserves the pre-fix unlagged tip`() {
        val web3j = mock<Web3j>()
        mockLatestBlockNumber(web3j, number = 100L)
        val reader = EvmChainReader("EVM_1", web3j, mockRepo, useFinalizedTag = false, confirmations = 0L)

        val bound = reader.resolveScanBound()

        assertEquals(100L, bound.blockNumber)
    }

    @Test
    fun `resolveScanBound depth heuristic never goes negative`() {
        val web3j = mock<Web3j>()
        mockLatestBlockNumber(web3j, number = 5L)
        val reader = EvmChainReader("EVM_1", web3j, mockRepo, useFinalizedTag = false, confirmations = 12L)

        val bound = reader.resolveScanBound()

        assertEquals(0L, bound.blockNumber)
    }

    // ── verifyLogStillPresent ────────────────────────────────────────────────

    private fun mockTransactionReceipt(
        web3j: Web3j,
        blockNumber: Long?,
        logIndexes: List<Long> = emptyList(),
        hasError: Boolean = false,
    ) {
        val request = mock<Request<*, EthGetTransactionReceipt>>()
        val response = mock<EthGetTransactionReceipt>()
        whenever(web3j.ethGetTransactionReceipt(any())).thenReturn(request)
        whenever(request.send()).thenReturn(response)
        whenever(response.hasError()).thenReturn(hasError)
        if (!hasError && blockNumber != null) {
            val receipt = mock<TransactionReceipt>()
            whenever(receipt.blockNumber).thenReturn(BigInteger.valueOf(blockNumber))
            val logs =
                logIndexes.map { idx ->
                    mock<Log>().also { whenever(it.logIndex).thenReturn(BigInteger.valueOf(idx)) }
                }
            whenever(receipt.logs).thenReturn(logs)
            whenever(response.transactionReceipt).thenReturn(Optional.of(receipt))
        } else {
            whenever(response.transactionReceipt).thenReturn(Optional.empty())
        }
    }

    @Test
    fun `verifyLogStillPresent returns true when the receipt exists at the expected block with a matching log index`() {
        val web3j = mock<Web3j>()
        mockTransactionReceipt(web3j, blockNumber = 100L, logIndexes = listOf(0L, 2L))
        val reader = EvmChainReader("EVM_1", web3j, mockRepo)

        assertTrue(reader.verifyLogStillPresent(txHash, logIndex = 2, expectedBlockNumber = 100L))
    }

    @Test
    fun `verifyLogStillPresent returns false when the receipt is missing entirely (reorged out)`() {
        val web3j = mock<Web3j>()
        mockTransactionReceipt(web3j, blockNumber = null)
        val reader = EvmChainReader("EVM_1", web3j, mockRepo)

        assertFalse(reader.verifyLogStillPresent(txHash, logIndex = 2, expectedBlockNumber = 100L))
    }

    @Test
    fun `verifyLogStillPresent returns false when the receipt has moved to a different block (re-mined)`() {
        val web3j = mock<Web3j>()
        mockTransactionReceipt(web3j, blockNumber = 105L, logIndexes = listOf(2L))
        val reader = EvmChainReader("EVM_1", web3j, mockRepo)

        assertFalse(reader.verifyLogStillPresent(txHash, logIndex = 2, expectedBlockNumber = 100L))
    }

    @Test
    fun `verifyLogStillPresent returns false when the log index is no longer present in the receipt`() {
        val web3j = mock<Web3j>()
        mockTransactionReceipt(web3j, blockNumber = 100L, logIndexes = listOf(0L, 1L))
        val reader = EvmChainReader("EVM_1", web3j, mockRepo)

        assertFalse(reader.verifyLogStillPresent(txHash, logIndex = 2, expectedBlockNumber = 100L))
    }

    @Test
    fun `verifyLogStillPresent returns false on RPC error`() {
        val web3j = mock<Web3j>()
        mockTransactionReceipt(web3j, blockNumber = 100L, hasError = true)
        val reader = EvmChainReader("EVM_1", web3j, mockRepo)

        assertFalse(reader.verifyLogStillPresent(txHash, logIndex = 2, expectedBlockNumber = 100L))
    }

    @Test
    fun `verifyLogStillPresent returns false when the RPC call throws`() {
        val web3j = mock<Web3j>()
        val request = mock<Request<*, EthGetTransactionReceipt>>()
        whenever(web3j.ethGetTransactionReceipt(any())).thenReturn(request)
        whenever(request.send()).thenThrow(IOException("connection reset"))
        val reader = EvmChainReader("EVM_1", web3j, mockRepo)

        assertFalse(reader.verifyLogStillPresent(txHash, logIndex = 2, expectedBlockNumber = 100L))
    }

    @Test
    fun `poll skips scanning entirely when the resolved bound has not advanced past checkpoint`() {
        val web3j = mock<Web3j>()
        mockFinalizedBlock(web3j, number = 100L)
        val reader = EvmChainReader("EVM_1", web3j, mockRepo)

        val result = reader.poll(checkpoint = 100L)

        assertEquals(emptyList<DetectedTransfer>(), result)
        verify(web3j, never()).ethGetLogs(any())
    }

    @Test
    fun `paddedAddress pads 20-byte address to 32-byte topic`() {
        val address = "0xAbCdEf1234567890AbCdEf1234567890AbCdEf12"
        val padded = EvmChainReader.paddedAddress(address)

        assertEquals("0x000000000000000000000000abcdef1234567890abcdef1234567890abcdef12", padded)
    }

    @Test
    fun `decodes USDT Transfer with 6 decimal precision`() {
        val usdtContract = "0xdAC17F958D2ee523a2206206994597C13D831ec7"
        val usdtWatched = WatchedAddress("EVM_1", watchedWallet, usdtContract, StablecoinToken.USDT, tenantId, "debit-1", "credit-1")
        val usdtRepo = mock<WatchedAddressRepository>()
        whenever(usdtRepo.findByChainKey("EVM_1")).thenReturn(listOf(usdtWatched))
        val usdtReader = EvmChainReader("EVM_1", mock<Web3j>(), usdtRepo)

        val result =
            usdtReader.decodeTransfer(
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
        val pyusdWatched = WatchedAddress("EVM_1", watchedWallet, pyusdContract, StablecoinToken.PYUSD, tenantId, "debit-1", "credit-1")
        val pyusdRepo = mock<WatchedAddressRepository>()
        whenever(pyusdRepo.findByChainKey("EVM_1")).thenReturn(listOf(pyusdWatched))
        val pyusdReader = EvmChainReader("EVM_1", mock<Web3j>(), pyusdRepo)

        val result =
            pyusdReader.decodeTransfer(
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
        val oneEther = "0x0000000000000000000000000000000000000000000000000de0b6b3a7640000"
        val brzWatched = WatchedAddress("EVM_1", watchedWallet, brzContract, StablecoinToken.BRZ, tenantId, "debit-1", "credit-1")
        val brzRepo = mock<WatchedAddressRepository>()
        whenever(brzRepo.findByChainKey("EVM_1")).thenReturn(listOf(brzWatched))
        val brzReader = EvmChainReader("EVM_1", mock<Web3j>(), brzRepo)

        val result =
            brzReader.decodeTransfer(
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
