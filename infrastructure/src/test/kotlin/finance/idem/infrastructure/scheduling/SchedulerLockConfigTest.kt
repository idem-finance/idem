package finance.idem.infrastructure.scheduling

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import javax.sql.DataSource

class SchedulerLockConfigTest {

    @Test
    fun `lockProvider returns a JdbcTemplateLockProvider`() {
        val dataSource = mock<DataSource>()

        val lockProvider = SchedulerLockConfig().lockProvider(dataSource)

        assertTrue(lockProvider is JdbcTemplateLockProvider)
    }

    @Test
    fun `lockingTaskExecutor wraps the given LockProvider in a DefaultLockingTaskExecutor`() {
        val lockProvider = mock<LockProvider>()

        val executor = SchedulerLockConfig().lockingTaskExecutor(lockProvider)

        assertTrue(executor is DefaultLockingTaskExecutor)
    }
}
