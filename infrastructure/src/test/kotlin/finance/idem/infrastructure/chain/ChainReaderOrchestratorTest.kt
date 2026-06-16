package finance.idem.infrastructure.chain

import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TransactionId
import finance.idem.core.chain.ChainCheckpoint
import finance.idem.core.chain.ChainCheckpointRepository
import finance.idem.core.monetary.OnChainEntry
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

class ChainReaderOrchestratorTest {

    private lateinit var checkpointRepository: ChainCheckpointRepository
    private lateinit var postTransactionUseCase: PostTransactionUseCase
    private lateinit var deadLetterRecorder: DeadLetterRecorder
    private lateinit var lockingTaskExecutor: LockingTaskExecutor

    private val recoveryExecutor: Executor = Executor { it.run() }

    private val tenantId = "00000000-0000-0000-0000-000000000001"
    private val debitAccountId = "00000000-0000-0000-0000-000000000002"
    private val creditAccountId = "00000000-0000-0000-0000-000000000003"

    @BeforeEach
    fun setUp() {
        checkpointRepository = mock()
        postTransactionUseCase = mock()
        deadLetterRecorder = mock()
        lockingTaskExecutor = mock()
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId(UUID.randomUUID())))
        // Simulate the lock being acquired: run the recovery task synchronously.
        doAnswer { invocation -> invocation.getArgument<Runnable>(0).run() }
            .whenever(lockingTaskExecutor).executeWithLock(any<Runnable>(), any())
    }

    private fun orchestrator(readers: List<ChainReader>): ChainReaderOrchestrator =
        ChainReaderOrchestrator(
            readers, checkpointRepository, postTransactionUseCase, deadLetterRecorder, recoveryExecutor,
        ).also { it.lockingTaskExecutor = lockingTaskExecutor }

    private fun fakeReader(chainKey: String, vararg transfers: DetectedTransfer): ChainReader {
        val reader = mock<ChainReader>()
        whenever(reader.chainKey).thenReturn(chainKey)
        whenever(reader.poll(any())).thenReturn(transfers.toList())
        return reader
    }

    private fun transfer(chainKey: String, blockNumber: Long, txHash: String = "0xhash"): DetectedTransfer {
        val watchedAddress = WatchedAddress(
            chainKey = chainKey,
            walletAddress = "0xabc",
            tokenContract = "0xdef",
            token = StablecoinToken.USDC,
            tenantId = tenantId,
            debitAccountId = debitAccountId,
            creditAccountId = creditAccountId,
        )
        return DetectedTransfer(
            idempotencyKey = "$chainKey:$txHash",
            entry = OnChainEntry(
                amount = MonetaryAmount.of("100.000000"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                txHash = txHash,
                blockNumber = blockNumber,
                walletAddress = watchedAddress.walletAddress,
                tokenContract = watchedAddress.tokenContract,
            ),
            watchedAddress = watchedAddress,
        )
    }

    @Test
    fun `onApplicationStarted polls EVM and Solana readers but not Tron`() {
        val evmReader = fakeReader("EVM_1")
        val solanaReader = fakeReader("SOLANA")
        val tronReader = fakeReader("TRON")

        val orchestrator = orchestrator(listOf(evmReader, solanaReader, tronReader))
        orchestrator.onApplicationStarted()

        verify(evmReader).poll(0L)
        verify(solanaReader).poll(0L)
        verify(tronReader, never()).poll(any())
    }

    @Test
    fun `pollTron polls only Tron readers`() {
        val evmReader = fakeReader("EVM_1")
        val tronReader = fakeReader("TRON")

        val orchestrator = orchestrator(listOf(evmReader, tronReader))
        orchestrator.pollTron()

        verify(tronReader).poll(0L)
        verify(evmReader, never()).poll(any())
    }

    @Test
    fun `startup recovery posts transfers with createdBy chain-recovery`() {
        val xfer = transfer("EVM_1", blockNumber = 100L)
        val evmReader = fakeReader("EVM_1", xfer)

        val orchestrator = orchestrator(listOf(evmReader))
        orchestrator.onApplicationStarted()

        val captor = argumentCaptor<PostTransactionCommand>()
        verify(postTransactionUseCase).execute(captor.capture())
        assertEquals("chain-recovery", captor.firstValue.createdBy)
        assertEquals(xfer.idempotencyKey, captor.firstValue.idempotencyKey)
    }

    @Test
    fun `tron poll posts transfers with createdBy tron-poller`() {
        val xfer = transfer("TRON", blockNumber = 200L)
        val tronReader = fakeReader("TRON", xfer)

        val orchestrator = orchestrator(listOf(tronReader))
        orchestrator.pollTron()

        val captor = argumentCaptor<PostTransactionCommand>()
        verify(postTransactionUseCase).execute(captor.capture())
        assertEquals("tron-poller", captor.firstValue.createdBy)
    }

    @Test
    fun `checkpoint advances to the max transfer block number`() {
        val xfer1 = transfer("EVM_1", blockNumber = 100L, txHash = "0x1")
        val xfer2 = transfer("EVM_1", blockNumber = 150L, txHash = "0x2")
        val evmReader = fakeReader("EVM_1", xfer1, xfer2)
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(ChainCheckpoint("EVM_1", 50L, Instant.now()))

        val orchestrator = orchestrator(listOf(evmReader))
        orchestrator.onApplicationStarted()

        verify(checkpointRepository).save("EVM_1", 150L)
    }

    @Test
    fun `checkpoint is left unchanged when no transfers are found`() {
        val evmReader = fakeReader("EVM_1")
        whenever(checkpointRepository.findByChainKey("EVM_1")).thenReturn(ChainCheckpoint("EVM_1", 50L, Instant.now()))

        val orchestrator = orchestrator(listOf(evmReader))
        orchestrator.onApplicationStarted()

        verify(checkpointRepository, never()).save(any(), any())
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `a failing reader does not prevent other readers from being polled`() {
        val failingReader = mock<ChainReader>()
        whenever(failingReader.chainKey).thenReturn("EVM_1")
        whenever(failingReader.poll(any())).thenThrow(RuntimeException("RPC down"))

        val workingReader = fakeReader("SOLANA")

        val orchestrator = orchestrator(listOf(failingReader, workingReader))
        orchestrator.onApplicationStarted()

        verify(workingReader).poll(0L)
    }

    @Test
    fun `a failed postTransactionUseCase execute is logged but checkpoint still advances`() {
        val xfer = transfer("EVM_1", blockNumber = 100L)
        val evmReader = fakeReader("EVM_1", xfer)
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.failure(RuntimeException("conflict")))

        val orchestrator = orchestrator(listOf(evmReader))
        orchestrator.onApplicationStarted()

        verify(checkpointRepository).save("EVM_1", 100L)
    }

    @Test
    fun `a failed postTransactionUseCase execute delegates to DeadLetterRecorder`() {
        val xfer = transfer("EVM_1", blockNumber = 100L)
        val evmReader = fakeReader("EVM_1", xfer)
        val error = RuntimeException("conflict")
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.failure(error))

        val orchestrator = orchestrator(listOf(evmReader))
        orchestrator.onApplicationStarted()

        verify(deadLetterRecorder).record(xfer, "EVM_1", "chain-recovery", error, "EVM_1")
    }

    @Test
    fun `onApplicationStarted submits the recovery sweep to chainRecoveryExecutor instead of running inline`() {
        val evmReader = fakeReader("EVM_1")
        val executor = mock<Executor>()

        val orchestrator = ChainReaderOrchestrator(
            listOf(evmReader), checkpointRepository, postTransactionUseCase, deadLetterRecorder, executor,
        ).also { it.lockingTaskExecutor = lockingTaskExecutor }
        orchestrator.onApplicationStarted()

        val taskCaptor = argumentCaptor<Runnable>()
        verify(executor).execute(taskCaptor.capture())
        verify(evmReader, never()).poll(any())

        taskCaptor.firstValue.run()

        verify(evmReader).poll(0L)
    }

    @Test
    fun `onApplicationStarted runs the recovery sweep under a chainRecoverySweep lock`() {
        val evmReader = fakeReader("EVM_1")

        val orchestrator = orchestrator(listOf(evmReader))
        orchestrator.onApplicationStarted()

        val lockConfigCaptor = argumentCaptor<LockConfiguration>()
        verify(lockingTaskExecutor).executeWithLock(any<Runnable>(), lockConfigCaptor.capture())
        assertEquals(ChainReaderOrchestrator.RECOVERY_SWEEP_LOCK_NAME, lockConfigCaptor.firstValue.name)
    }

    @Test
    fun `recovery sweep does not poll when the chainRecoverySweep lock is held by another replica`() {
        val evmReader = fakeReader("EVM_1")
        doAnswer { /* lock not acquired: task is never run */ }
            .whenever(lockingTaskExecutor).executeWithLock(any<Runnable>(), any())

        val orchestrator = orchestrator(listOf(evmReader))
        orchestrator.onApplicationStarted()

        verify(evmReader, never()).poll(any())
        verify(postTransactionUseCase, never()).execute(any())
    }
}
