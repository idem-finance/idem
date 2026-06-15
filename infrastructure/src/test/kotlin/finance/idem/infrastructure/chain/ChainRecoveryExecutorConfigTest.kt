package finance.idem.infrastructure.chain

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ChainRecoveryExecutorConfigTest {

    @Test
    fun `chainRecoveryExecutor runs tasks on named virtual threads`() {
        val executor = ChainRecoveryExecutorConfig().chainRecoveryExecutor()
        try {
            val threadName = CompletableFuture<String>()
            val isVirtual = CompletableFuture<Boolean>()

            executor.execute {
                threadName.complete(Thread.currentThread().name)
                isVirtual.complete(Thread.currentThread().isVirtual())
            }

            assertTrue(threadName.get(5, TimeUnit.SECONDS).startsWith("chain-recovery-"))
            assertTrue(isVirtual.get(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `chainRecoveryExecutor shuts down promptly via shutdownNow`() {
        val executor = ChainRecoveryExecutorConfig().chainRecoveryExecutor()

        executor.shutdownNow()

        assertTrue(executor.isShutdown)
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
}
