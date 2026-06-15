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
 * lifetime, so per-task thread cost is irrelevant. `ExecutorService` implements
 * [AutoCloseable], so Spring's inferred destroy method (`close()`) shuts it down on
 * context close with no extra wiring.
 */
@Configuration
class ChainRecoveryExecutorConfig {

    @Bean
    fun chainRecoveryExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
}
