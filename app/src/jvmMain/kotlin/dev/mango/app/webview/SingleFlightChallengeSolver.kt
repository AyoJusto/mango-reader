package dev.mango.app.webview

import dev.mango.core.domain.ChallengeSolver
import kotlinx.coroutines.sync.Mutex

/** Concurrent solves fail fast with false while one is in flight; one embedded browser at a time. */
class SingleFlightChallengeSolver(private val delegate: ChallengeSolver) : ChallengeSolver {
    private val inFlight = Mutex()

    override suspend fun solve(sourceId: String, url: String): Boolean {
        if (!inFlight.tryLock()) return false
        return try {
            delegate.solve(sourceId, url)
        } finally {
            inFlight.unlock()
        }
    }
}
