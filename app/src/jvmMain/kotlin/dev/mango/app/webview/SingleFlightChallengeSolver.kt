package dev.mango.app.webview

import dev.mango.core.domain.ChallengeSolver
import kotlinx.coroutines.sync.Mutex

/**
 * App-wide one-solve-at-a-time gate over any [ChallengeSolver]. A solve already in flight
 * makes every other concurrent [solve] return false immediately (fail-fast: the caller's
 * screen just re-enables its button, same as a user closing the window) instead of opening
 * a second browser. Screens keep their local button-disable flags; this is the layer that
 * actually enforces the policy across screens.
 */
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
