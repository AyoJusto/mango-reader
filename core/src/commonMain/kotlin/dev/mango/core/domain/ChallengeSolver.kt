package dev.mango.core.domain

/** Solves a source's anti-bot challenge in an embedded browser, persisting clearance cookies and the matching UA; implemented in :app. */
interface ChallengeSolver {
    /** Opens [url] for [sourceId]; true once clearance cookies are stored, false if closed unsolved or refused as concurrent. */
    suspend fun solve(sourceId: String, url: String): Boolean
}
