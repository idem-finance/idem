package finance.idem.infrastructure.chain

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Executor for [ChainReaderOrchestrator]'s one-time startup recovery sweep.
 *
 * Virtual threads suit this workload: the sweep is blocking-I/O-bound (wide `ethGetLogs`
 * ranges, paginated Solana `getSignaturesForAddress`) and fires at most once per process
 * lifetime, so per-task thread cost is irrelevant. Threads are named `chain-recovery-N` so
 * recovery-sweep log lines can be correlated by thread name.
 *
 * Destroy method is `shutdownNow()`, not the JDK-inferred `close()`: the default `close()`
 * blocks up to 1 day waiting for in-flight tasks, which would hang context shutdown if the
 * sweep is still running. The sweep resumes from the last saved `ChainCheckpoint` on next
 * startup, so interrupting it on shutdown is safe.
 */
@Configuration
class ChainRecoveryExecutorConfig {
    @Bean(name = [BEAN_NAME], destroyMethod = "shutdownNow")
    fun chainRecoveryExecutor(): ExecutorService =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chain-recovery-", 0).factory())

    companion object {
        const val BEAN_NAME = "chainRecoveryExecutor"
    }
}
