package dev.mango.core.domain

/**
 * Solves a source's anti-bot challenge ([ChallengeRequiredException]) by opening an embedded
 * browser the user clicks through. On success it persists the harvested clearance cookies and
 * the matching user-agent for the source (cf_clearance is UA-bound), so the source's normal
 * requests go through afterward. Screens call this and re-run the failed action on true.
 *
 * The implementation lives in :app (it needs an embedded browser); the port is here so screens
 * reach it through the same ports-only boundary as everything else.
 */
interface ChallengeSolver {
    /**
     * Opens the challenge [url] in a browser window for [sourceId]. Returns true once clearance
     * cookies were harvested and stored, false if the user closed the window without passing
     * the challenge. Suspends until the window resolves either way. Implementations may refuse
     * a solve that would run concurrently with another by returning false immediately.
     */
    suspend fun solve(sourceId: String, url: String): Boolean
}
