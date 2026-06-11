package finance.idem.infrastructure.chain

import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.infrastructure.chain.TronChainReader.TronTokenInfo
import finance.idem.infrastructure.chain.TronChainReader.TronTransfer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class TronChainReaderTest {

    private val usdtContract = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
    private val usdcContract = "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8"
    private val watchedWallet = "TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7"
    private val senderWallet  = "TJCnKsPa7y5okkXvQAidZBzqx3QyQ6sxMW"
    private val txHash        = "abc123def456abc123def456abc123def456abc123def456abc123def456abc1"
    private val blockId       = 55_000_000L

    private val watchedAddress = WatchedAddress(
        chainKey = "TRON",
        walletAddress = watchedWallet,
        tokenContract = usdtContract,
        token = StablecoinToken.USDT,
        tenantId = "tenant-1",
        debitAccountId = "debit-1",
        creditAccountId = "credit-1",
    )

    private val mockRepo = mock<WatchedAddressRepository>()
    private val reader = TronChainReader(
        apiUrl = "http://localhost:9999",
        watchedAddressRepository = mockRepo,
        requestDelayMs = 0,
    )

    @BeforeEach
    fun setUp() {
        whenever(mockRepo.findByChainKey("TRON")).thenReturn(listOf(watchedAddress))
    }

    @Test
    fun `decodes incoming USDT transfer`() {
        val transfer = transfer(to = watchedWallet, contract = usdtContract, quant = "1000000", decimals = 6)

        val result = reader.decodeTransfer(transfer, watchedAddress)

        assertEquals("TRON:$txHash", result!!.idempotencyKey)
        assertEquals(MonetaryAmount.of(BigDecimal("1.000000")), result.entry.amount)
        assertEquals(StablecoinToken.USDT, result.entry.token)
        assertEquals(ChainId.TRON, result.entry.chainId)
        assertEquals(txHash, result.entry.txHash)
        assertEquals(blockId, result.entry.blockNumber)
        assertEquals(watchedWallet.lowercase(), result.entry.walletAddress)
        assertEquals(usdtContract.lowercase(), result.entry.tokenContract)
        assertEquals(senderWallet.lowercase(), result.entry.fromAddress)
        assertEquals(watchedAddress, result.watchedAddress)
    }

    @Test
    fun `decodes incoming USDC transfer`() {
        val usdcWatched = watchedAddress.copy(tokenContract = usdcContract, token = StablecoinToken.USDC)
        val transfer = transfer(to = watchedWallet, contract = usdcContract, quant = "5000000", decimals = 6)

        val result = reader.decodeTransfer(transfer, usdcWatched)

        assertEquals(StablecoinToken.USDC, result!!.entry.token)
        assertEquals(MonetaryAmount.of(BigDecimal("5.000000")), result.entry.amount)
    }

    @Test
    fun `returns null for outgoing transfer — to address does not match watched wallet`() {
        val transfer = transfer(to = senderWallet, contract = usdtContract, quant = "1000000", decimals = 6)

        assertNull(reader.decodeTransfer(transfer, watchedAddress))
    }

    @Test
    fun `returns null when token contract does not match`() {
        val transfer = transfer(to = watchedWallet, contract = usdcContract, quant = "1000000", decimals = 6)

        assertNull(reader.decodeTransfer(transfer, watchedAddress))
    }

    @Test
    fun `returns null when finalResult is FAILED`() {
        val transfer = transfer(
            to = watchedWallet, contract = usdtContract, quant = "1000000",
            decimals = 6, finalResult = "FAILED",
        )

        assertNull(reader.decodeTransfer(transfer, watchedAddress))
    }

    @Test
    fun `processes transfer when finalResult is null — legacy response format`() {
        val transfer = transfer(
            to = watchedWallet, contract = usdtContract, quant = "1000000",
            decimals = 6, finalResult = null,
        )

        assertEquals("TRON:$txHash", reader.decodeTransfer(transfer, watchedAddress)!!.idempotencyKey)
    }

    @Test
    fun `returns null for unsupported token — BRZ not supported on Tron`() {
        val brzWatched = watchedAddress.copy(token = StablecoinToken.BRZ, tokenContract = "TBrzContract")
        val transfer = transfer(to = watchedWallet, contract = "TBrzContract", quant = "1000000", decimals = 18)

        assertNull(reader.decodeTransfer(transfer, brzWatched))
    }

    @Test
    fun `returns null when on-chain decimals differ from expected`() {
        val transfer = transfer(to = watchedWallet, contract = usdtContract, quant = "1000000", decimals = 18)

        assertNull(reader.decodeTransfer(transfer, watchedAddress))
    }

    @Test
    fun `returns null when quant is not parseable as Long`() {
        val transfer = transfer(to = watchedWallet, contract = usdtContract, quant = "not_a_number", decimals = 6)

        assertNull(reader.decodeTransfer(transfer, watchedAddress))
    }

    @Test
    fun `returns null when amount is zero`() {
        val transfer = transfer(to = watchedWallet, contract = usdtContract, quant = "0", decimals = 6)

        assertNull(reader.decodeTransfer(transfer, watchedAddress))
    }

    @Test
    fun `returns null when amount is negative`() {
        val transfer = transfer(to = watchedWallet, contract = usdtContract, quant = "-1000000", decimals = 6)

        assertNull(reader.decodeTransfer(transfer, watchedAddress))
    }

    @Test
    fun `to address comparison is case-insensitive`() {
        val transfer = transfer(to = watchedWallet.uppercase(), contract = usdtContract, quant = "1000000", decimals = 6)

        assertEquals("TRON:$txHash", reader.decodeTransfer(transfer, watchedAddress)!!.idempotencyKey)
    }

    @Test
    fun `token contract comparison is case-insensitive`() {
        val transfer = transfer(to = watchedWallet, contract = usdtContract.uppercase(), quant = "1000000", decimals = 6)

        assertEquals("TRON:$txHash", reader.decodeTransfer(transfer, watchedAddress)!!.idempotencyKey)
    }

    @Test
    fun `wallet and contract stored lowercase in OnChainEntry`() {
        val transfer = transfer(to = watchedWallet.uppercase(), contract = usdtContract.uppercase(), quant = "1000000", decimals = 6)

        val result = reader.decodeTransfer(transfer, watchedAddress)!!

        assertEquals(watchedWallet.lowercase(), result.entry.walletAddress)
        assertEquals(usdtContract.lowercase(), result.entry.tokenContract)
    }

    @Test
    fun `poll returns empty list when no watched addresses in db`() {
        val emptyRepo = mock<WatchedAddressRepository>()
        whenever(emptyRepo.findByChainKey("TRON")).thenReturn(emptyList())
        val emptyReader = TronChainReader("http://localhost:9999", emptyRepo, requestDelayMs = 0)

        assertEquals(emptyList<DetectedTransfer>(), emptyReader.poll(0L))
    }

    @Test
    fun `implements Closeable — close does not throw`() {
        val closeableReader = TronChainReader("http://localhost:9999", mockRepo, requestDelayMs = 0)
        closeableReader.close()
    }

    private fun transfer(
        to: String,
        contract: String,
        quant: String,
        decimals: Int,
        finalResult: String? = "SUCCESS",
    ) = TronTransfer(
        txHash = txHash,
        blockId = blockId,
        blockTs = 1_699_000_000_000L,
        fromAddress = senderWallet,
        toAddress = to,
        quant = quant,
        finalResult = finalResult,
        tokenInfo = TronTokenInfo(tokenId = contract, symbol = "USDT", decimals = decimals),
    )
}
