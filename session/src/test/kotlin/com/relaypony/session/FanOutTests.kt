package com.relaypony.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FanOutTests {

    @Test
    fun runsEveryTargetAndIsolatesFailures() {
        val results = ConcurrentHashMap<String, Result<Unit>>()
        FanOut.run(
            targets = listOf("a", "b", "c"),
            onResult = { target, outcome -> results[target] = outcome },
        ) { target ->
            if (target == "b") throw RuntimeException("boom")
        }
        assertEquals(setOf("a", "b", "c"), results.keys)
        assertTrue(results["a"]!!.isSuccess)
        assertTrue(results["c"]!!.isSuccess, "c must still run despite b failing")
        assertTrue(results["b"]!!.isFailure)
        assertEquals("boom", results["b"]!!.exceptionOrNull()?.message)
    }

    @Test
    fun actuallyRunsInParallel() {
        // Each task signals it has started, then waits for ALL to have started. If the fan-out ran
        // sequentially, the first task would block forever waiting for the others, the latch would
        // never reach zero, and no task would record progress. Only true concurrency lets them all
        // proceed.
        val n = 5
        val allStarted = CountDownLatch(n)
        val proceeded = AtomicInteger(0)
        FanOut.run((1..n).toList()) {
            allStarted.countDown()
            if (allStarted.await(3, TimeUnit.SECONDS)) proceeded.incrementAndGet()
        }
        assertEquals(n, proceeded.get(), "all tasks must run concurrently")
    }

    @Test
    fun emptyTargetsIsNoOp() {
        FanOut.run(emptyList<String>()) { fail("action must not run for empty targets") }
    }
}
