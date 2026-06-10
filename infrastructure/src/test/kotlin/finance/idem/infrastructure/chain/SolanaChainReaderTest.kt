package finance.idem.infrastructure.chain

import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.infrastructure.chain.SolanaChainReader.SolanaTokenBalance
import finance.idem.infrastructure.chain.SolanaChainReader.SolanaTransactionMeta
import finance.idem.infrastructure.chain.SolanaChainReader.SolanaTransactionResult
import finance.idem.infrastructure.chain.SolanaChainReader.SolanaUiTokenAmount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.net.http.HttpClient

class SolanaChainReaderTest {

    private val usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val usdtMint = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
    private val watchedWallet = "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS"
    private val signature = "5j7s6XxnkqxAbcDE1234567890abcdefghijklmnopqrstuvwxyz1234567"
    private val slot = 250_000_000L

    private val watchedAddress = WatchedAddress(
        chainKey = "SOLANA",
        walletAddress = watchedWallet,
        tokenContract = usdcMint,
        token = StablecoinToken.USDC,
        tenantId = "a1b2c3d4-0000-0000-0000-000000000001",
        debitAccountId = "a1b2c3d4-0000-0000-0000-000000000002",
        creditAccountId = "a1b2c3d4-0000-0000-0000-000000000003",
    )

    private val mockRepo = mock<WatchedAddressRepository>()
    private val reader = SolanaChainReader("http://localhost:9999", mockRepo)

    @BeforeEach
    fun setUp() {
        whenever(mockRepo.findByChainKey("SOLANA")).thenReturn(listOf(watchedAddress))
    }

    @Test
    fun `decodes incoming USDC transfer from token balance change`() {
        val tx = txWithBalanceChange(
            accountIndex = 2,
            mint = usdcMint,
            owner = watchedWallet,
            preAmount = 0L,
            postAmount = 1_000_000L,
            decimals = 6,
        )

        val result = reader.decodeTransfer(tx, signature, slot, watchedAddress)

        assertEquals("SOLANA:$signature:2", result!!.idempotencyKey)
        assertEquals(MonetaryAmount.of(BigDecimal("1.000000")), result.entry.amount)
        assertEquals(StablecoinToken.USDC, result.entry.token)
        assertEquals(ChainId.SOLANA, result.entry.chainId)
        assertEquals(signature, result.entry.txHash)
        assertEquals(slot, result.entry.blockNumber)
        assertEquals(watchedWallet, result.entry.walletAddress)
        assertEquals(usdcMint, result.entry.tokenContract)
        assertEquals(watchedAddress, result.watchedAddress)
    }

    @Test
    fun `idempotency key is disambiguated by accountIndex for multi-recipient transactions`() {
        val secondWallet = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
        val secondWatchedAddress = watchedAddress.copy(walletAddress = secondWallet)

        val tx = SolanaTransactionResult(
            slot = slot,
            meta = SolanaTransactionMeta(
                err = null,
                preTokenBalances = emptyList(),
                postTokenBalances = listOf(
                    SolanaTokenBalance(
                        accountIndex = 1,
                        mint = usdcMint,
                        owner = watchedWallet,
                        uiTokenAmount = SolanaUiTokenAmount(amount = "1000000", decimals = 6),
                    ),
                    SolanaTokenBalance(
                        accountIndex = 2,
                        mint = usdcMint,
                        owner = secondWallet,
                        uiTokenAmount = SolanaUiTokenAmount(amount = "2000000", decimals = 6),
                    ),
                ),
            ),
        )

        val first = reader.decodeTransfer(tx, signature, slot, watchedAddress)
        val second = reader.decodeTransfer(tx, signature, slot, secondWatchedAddress)

        assertEquals("SOLANA:$signature:1", first!!.idempotencyKey)
        assertEquals("SOLANA:$signature:2", second!!.idempotencyKey)
    }

    @Test
    fun `decodes USDT transfer with 6 decimal precision`() {
        val usdtWatched = watchedAddress.copy(tokenContract = usdtMint, token = StablecoinToken.USDT)
        val tx = txWithBalanceChange(
            accountIndex = 1,
            mint = usdtMint,
            owner = watchedWallet,
            preAmount = 500_000L,
            postAmount = 1_500_000L,
            decimals = 6,
        )

        val result = reader.decodeTransfer(tx, signature, slot, usdtWatched)

        assertEquals(StablecoinToken.USDT, result!!.entry.token)
        assertEquals(MonetaryAmount.of(BigDecimal("1.000000")), result.entry.amount)
    }

    @Test
    fun `returns null when meta is null`() {
        val tx = SolanaTransactionResult(slot = slot, meta = null)

        assertNull(reader.decodeTransfer(tx, signature, slot, watchedAddress))
    }

    @Test
    fun `returns null when transaction has error`() {
        val tx = SolanaTransactionResult(
            slot = slot,
            meta = SolanaTransactionMeta(err = mapOf("InstructionError" to listOf(0, "InvalidAccountData"))),
        )

        assertNull(reader.decodeTransfer(tx, signature, slot, watchedAddress))
    }

    @Test
    fun `returns null when no post token balances`() {
        val tx = SolanaTransactionResult(
            slot = slot,
            meta = SolanaTransactionMeta(err = null, postTokenBalances = null),
        )

        assertNull(reader.decodeTransfer(tx, signature, slot, watchedAddress))
    }

    @Test
    fun `returns null when watched wallet not in post balances`() {
        val tx = txWithBalanceChange(
            accountIndex = 2,
            mint = usdcMint,
            owner = "SomeOtherWallet111111111111111111111111111111",
            preAmount = 0L,
            postAmount = 1_000_000L,
            decimals = 6,
        )

        assertNull(reader.decodeTransfer(tx, signature, slot, watchedAddress))
    }

    @Test
    fun `returns null when mint does not match token contract`() {
        val tx = txWithBalanceChange(
            accountIndex = 2,
            mint = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
            owner = watchedWallet,
            preAmount = 0L,
            postAmount = 1_000_000L,
            decimals = 6,
        )

        assertNull(reader.decodeTransfer(tx, signature, slot, watchedAddress))
    }

    @Test
    fun `returns null when delta is zero — outgoing transfer or no change`() {
        val tx = txWithBalanceChange(
            accountIndex = 2,
            mint = usdcMint,
            owner = watchedWallet,
            preAmount = 1_000_000L,
            postAmount = 1_000_000L,
            decimals = 6,
        )

        assertNull(reader.decodeTransfer(tx, signature, slot, watchedAddress))
    }

    @Test
    fun `returns null when balance decreased — outgoing transfer`() {
        val tx = txWithBalanceChange(
            accountIndex = 2,
            mint = usdcMint,
            owner = watchedWallet,
            preAmount = 2_000_000L,
            postAmount = 1_000_000L,
            decimals = 6,
        )

        assertNull(reader.decodeTransfer(tx, signature, slot, watchedAddress))
    }

    @Test
    fun `owner comparison is case-insensitive`() {
        val tx = txWithBalanceChange(
            accountIndex = 2,
            mint = usdcMint,
            owner = watchedWallet.uppercase(),
            preAmount = 0L,
            postAmount = 1_000_000L,
            decimals = 6,
        )

        assertEquals("SOLANA:$signature:2", reader.decodeTransfer(tx, signature, slot, watchedAddress)!!.idempotencyKey)
    }

    @Test
    fun `mint comparison is case-insensitive`() {
        val tx = txWithBalanceChange(
            accountIndex = 2,
            mint = usdcMint.uppercase(),
            owner = watchedWallet,
            preAmount = 0L,
            postAmount = 1_000_000L,
            decimals = 6,
        )

        assertEquals("SOLANA:$signature:2", reader.decodeTransfer(tx, signature, slot, watchedAddress)!!.idempotencyKey)
    }

    @Test
    fun `poll returns empty list when no watched addresses in db`() {
        val emptyRepo = mock<WatchedAddressRepository>()
        whenever(emptyRepo.findByChainKey("SOLANA")).thenReturn(emptyList())
        val emptyReader = SolanaChainReader("http://localhost:9999", emptyRepo)

        val result = emptyReader.poll(0L)

        assertEquals(emptyList<DetectedTransfer>(), result)
    }

    @Test
    fun `returns null when RPC-reported decimals differ from known token decimals`() {
        val tx = txWithBalanceChange(
            accountIndex = 2,
            mint = usdcMint,
            owner = watchedWallet,
            preAmount = 0L,
            postAmount = 1_000_000L,
            decimals = 18, // wrong — USDC is always 6
        )

        assertNull(reader.decodeTransfer(tx, signature, slot, watchedAddress))
    }

    @Test
    fun `returns null for unsupported token type`() {
        val brzWatched = watchedAddress.copy(token = StablecoinToken.BRZ, tokenContract = "BrzContract")
        val tx = txWithBalanceChange(
            accountIndex = 2,
            mint = "BrzContract",
            owner = watchedWallet,
            preAmount = 0L,
            postAmount = 1_000_000_000_000_000_000L,
            decimals = 18,
        )

        assertNull(reader.decodeTransfer(tx, signature, slot, brzWatched))
    }

    @Test
    fun `returns null and logs warning when token amount is not parseable as Long`() {
        val tx = SolanaTransactionResult(
            slot = slot,
            meta = SolanaTransactionMeta(
                err = null,
                preTokenBalances = emptyList(),
                postTokenBalances = listOf(
                    SolanaTokenBalance(
                        accountIndex = 2,
                        mint = usdcMint,
                        owner = watchedWallet,
                        uiTokenAmount = SolanaUiTokenAmount(amount = "not_a_number", decimals = 6),
                    )
                ),
            ),
        )

        assertNull(reader.decodeTransfer(tx, signature, slot, watchedAddress))
    }

    @Test
    fun `returns null and logs warning when matching mint has null owner — legacy tx`() {
        val tx = SolanaTransactionResult(
            slot = slot,
            meta = SolanaTransactionMeta(
                err = null,
                preTokenBalances = emptyList(),
                postTokenBalances = listOf(
                    SolanaTokenBalance(
                        accountIndex = 2,
                        mint = usdcMint,
                        owner = null, // absent in legacy tx format
                        uiTokenAmount = SolanaUiTokenAmount(amount = "1000000", decimals = 6),
                    )
                ),
            ),
        )

        assertNull(reader.decodeTransfer(tx, signature, slot, watchedAddress))
    }

    @Test
    fun `implements Closeable — close does not throw`() {
        val closeableReader = SolanaChainReader("http://localhost:9999", mockRepo, HttpClient.newHttpClient())
        closeableReader.close()
    }

    private fun txWithBalanceChange(
        accountIndex: Int,
        mint: String,
        owner: String,
        preAmount: Long,
        postAmount: Long,
        decimals: Int,
    ): SolanaTransactionResult {
        val preBalances = if (preAmount > 0) listOf(
            SolanaTokenBalance(
                accountIndex = accountIndex,
                mint = mint,
                owner = owner,
                uiTokenAmount = SolanaUiTokenAmount(amount = preAmount.toString(), decimals = decimals),
            )
        ) else emptyList()

        val postBalances = listOf(
            SolanaTokenBalance(
                accountIndex = accountIndex,
                mint = mint,
                owner = owner,
                uiTokenAmount = SolanaUiTokenAmount(amount = postAmount.toString(), decimals = decimals),
            )
        )

        return SolanaTransactionResult(
            slot = slot,
            meta = SolanaTransactionMeta(
                err = null,
                preTokenBalances = preBalances,
                postTokenBalances = postBalances,
            ),
        )
    }
}
