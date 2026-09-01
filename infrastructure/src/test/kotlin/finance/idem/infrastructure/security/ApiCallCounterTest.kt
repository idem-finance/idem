package finance.idem.infrastructure.security

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

    @Test
    fun `concurrent increments and drains lose no counts`() {
        val tenantId = TenantId.generate()
        val incrementerThreads = 8
        val incrementsPerThread = 1000
        val totalExpected = (incrementerThreads * incrementsPerThread).toLong()

        val drainedTotal = AtomicLong(0)
        val incrementExecutor = Executors.newFixedThreadPool(incrementerThreads)
        val drainerRunning = AtomicBoolean(true)
        val drainerThread =
            Thread {
                while (drainerRunning.get()) {
                    counter.drainAndReset()[tenantId]?.let { drainedTotal.addAndGet(it) }
                }
            }
        drainerThread.start()
        try {
            val futures =
                (1..incrementerThreads).map {
                    incrementExecutor.submit { repeat(incrementsPerThread) { counter.increment(tenantId) } }
                }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            incrementExecutor.shutdown()
            drainerRunning.set(false)
            drainerThread.join(5000)
        }
        // Final drain picks up anything left uncollected by the racing drainer thread.
        drainedTotal.addAndGet(counter.drainAndReset()[tenantId] ?: 0L)

        assertEquals(totalExpected, drainedTotal.get(), "no increments should be lost to a race with a concurrent drain")
    }
}
