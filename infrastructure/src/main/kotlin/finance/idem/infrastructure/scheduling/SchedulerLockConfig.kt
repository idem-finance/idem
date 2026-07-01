package finance.idem.infrastructure.scheduling

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * Enables ShedLock so `@Scheduled` tasks (and the chain-recovery sweep, via
 * [LockingTaskExecutor]) run on at most one replica at a time in multi-replica
 * deployments (#89).
 *
 * Active only when `idem.scheduling.distributed-lock.enabled=true` (off by default for
 * standalone single-instance deployments). Cloud/GKE deployments must set
 * `IDEM_SCHEDULING_DISTRIBUTED_LOCK_ENABLED=true`.
 *
 * [JdbcTemplateLockProvider.Configuration.usingDbTime] is used because lock expiry must be
 * compared against a single clock — relying on each pod's wall clock would be unsafe under
 * clock skew across GKE nodes.
 */
@Configuration
@ConditionalOnProperty(name = ["idem.scheduling.distributed-lock.enabled"], havingValue = "true")
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
class SchedulerLockConfig {
    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration
                .builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )

    @Bean
    fun lockingTaskExecutor(lockProvider: LockProvider): LockingTaskExecutor = DefaultLockingTaskExecutor(lockProvider)
}
