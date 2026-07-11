package dev.mango.app

import dev.mango.app.webview.SingleFlightChallengeSolver
import dev.mango.core.domain.ChallengeSolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleFlightChallengeSolverTest {
    @Test
    fun secondSolveReturnsImmediatelyWhenFirstIsSuspended() {
        val invocationCount = IntArray(1)
        val deferred = CompletableDeferred<Boolean>()
        val delegate = object : ChallengeSolver {
            override suspend fun solve(sourceId: String, url: String): Boolean {
                invocationCount[0]++
                return deferred.await()
            }
        }
        val gate = SingleFlightChallengeSolver(delegate)

        runBlocking {
            var firstResult: Boolean? = null
            var secondResult: Boolean? = null

            launch {
                firstResult = gate.solve("source1", "url1")
            }

            launch {
                // Give the first solve time to start
                kotlinx.coroutines.delay(100)
                secondResult = gate.solve("source2", "url2")
            }

            // Let second solve run and return
            kotlinx.coroutines.delay(200)
            assertEquals(1, invocationCount[0], "delegate should only be invoked once while first solve is in flight")
            assertFalse(secondResult!!, "second solve should return false immediately")

            // Complete the deferred so first solve can finish
            deferred.complete(true)

            // Give first solve time to complete
            kotlinx.coroutines.delay(100)
            assertTrue(firstResult!!, "first solve should return true after deferred completes")
        }
    }

    @Test
    fun newSolveReachesDelegateAfterPreviousSolveCompletes() {
        val invocationCount = IntArray(1)
        val deferred1 = CompletableDeferred<Boolean>()
        val deferred2 = CompletableDeferred<Boolean>()
        val delegate = object : ChallengeSolver {
            override suspend fun solve(sourceId: String, url: String): Boolean {
                invocationCount[0]++
                return if (invocationCount[0] == 1) {
                    deferred1.await()
                } else {
                    deferred2.await()
                }
            }
        }
        val gate = SingleFlightChallengeSolver(delegate)

        runBlocking {
            launch {
                gate.solve("source1", "url1")
            }

            kotlinx.coroutines.delay(100)
            assertEquals(1, invocationCount[0])
            deferred1.complete(true)

            kotlinx.coroutines.delay(100)

            launch {
                gate.solve("source2", "url2")
            }

            kotlinx.coroutines.delay(100)
            assertEquals(2, invocationCount[0], "after first solve completes, second solve should reach delegate")
            deferred2.complete(false)
        }
    }

    @Test
    fun gateIsReleasedEvenWhenDelegateThrows() {
        val invocationCount = IntArray(1)
        val throwDeferred = CompletableDeferred<Unit>()
        val delegate = object : ChallengeSolver {
            override suspend fun solve(sourceId: String, url: String): Boolean {
                invocationCount[0]++
                if (invocationCount[0] == 1) {
                    throwDeferred.await()
                    throw RuntimeException("delegate error")
                }
                return true
            }
        }
        val gate = SingleFlightChallengeSolver(delegate)

        runBlocking {
            var firstThrew = false
            var secondResult: Boolean? = null

            launch {
                try {
                    gate.solve("source1", "url1")
                } catch (e: RuntimeException) {
                    firstThrew = true
                }
            }

            kotlinx.coroutines.delay(100)
            throwDeferred.complete(Unit)

            kotlinx.coroutines.delay(100)
            assertTrue(firstThrew, "first solve should propagate the RuntimeException")

            launch {
                secondResult = gate.solve("source2", "url2")
            }

            kotlinx.coroutines.delay(100)
            assertEquals(2, invocationCount[0], "after exception, second solve should reach delegate")
            assertTrue(secondResult!!, "second solve should return true")
        }
    }
}
