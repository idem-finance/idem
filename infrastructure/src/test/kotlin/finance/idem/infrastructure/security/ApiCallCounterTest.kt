package finance.idem.infrastructure.security

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiCallCounterTest {
    private val counter = ApiCallCounter()

    @Test
    fun `drainAndReset returns nothing when no tenant has been incremented`() {
        assertTrue(counter.drainAndReset().isEmpty())
    }

    @Test
    fun `increment accumulates per tenant`() {
        val tenantId = TenantId.generate()
        repeat(3) { counter.increment(tenantId) }

        assertEquals(mapOf(tenantId to 3L), counter.drainAndReset())
    }

    @Test
    fun `drainAndReset zeroes the counter — a second call returns nothing new`() {
        val tenantId = TenantId.generate()
        counter.increment(tenantId)

        counter.drainAndReset()
        val second = counter.drainAndReset()

        assertTrue(second.isEmpty())
    }

    @Test
    fun `keeps separate tallies per tenant`() {
        val tenantA = TenantId.generate()
        val tenantB = TenantId.generate()
        counter.increment(tenantA)
        repeat(2) { counter.increment(tenantB) }

        val drained = counter.drainAndReset()

        assertEquals(1L, drained[tenantA])
        assertEquals(2L, drained[tenantB])
    }

    @Test
    fun `concurrent increments from multiple threads sum correctly`() {
        val tenantId = TenantId.generate()
        val threads = 8
        val incrementsPerThread = 500
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (1..threads).map {
                    executor.submit { repeat(incrementsPerThread) { counter.increment(tenantId) } }
                }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        assertEquals((threads * incrementsPerThread).toLong(), counter.drainAndReset()[tenantId])
    }
}
