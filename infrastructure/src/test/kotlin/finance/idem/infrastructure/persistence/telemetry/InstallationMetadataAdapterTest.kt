package finance.idem.infrastructure.persistence.telemetry

import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InstallationMetadataAdapter::class)
class InstallationMetadataAdapterTest : SharedPostgresTestBase() {
    @Autowired
    lateinit var adapter: InstallationMetadataAdapter

    @Autowired
    lateinit var jpaRepository: InstallationMetadataJpaRepository

    @Test
    fun `getOrCreateId creates exactly one row on first call and returns a UUID`() {
        val id = adapter.getOrCreateId()

        assertNotNull(id)
        assertEquals(1, jpaRepository.count())
        assertEquals(id, jpaRepository.findById(1).orElseThrow().id)
    }

    @Test
    fun `getOrCreateId returns the same UUID on repeated calls without creating a second row`() {
        val first = adapter.getOrCreateId()
        val second = adapter.getOrCreateId()
        val third = adapter.getOrCreateId()

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(1, jpaRepository.count())
    }
}
