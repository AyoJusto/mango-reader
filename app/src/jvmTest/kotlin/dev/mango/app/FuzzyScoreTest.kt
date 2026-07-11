package dev.mango.app

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for [fuzzyScore] — pure function, no Compose, no coroutines. */
class FuzzyScoreTest {
    @Test
    fun subsequenceInOrderMatches() {
        assertNotNull(fuzzyScore("slv", "Solo Leveling"))
    }

    @Test
    fun charactersOutOfOrderDoNotMatch() {
        assertNull(fuzzyScore("vls", "Solo Leveling"))
    }

    @Test
    fun missingCharacterDoesNotMatch() {
        assertNull(fuzzyScore("xyz", "Solo Leveling"))
    }

    @Test
    fun prefixMatchScoresHigherThanMidStringMatch() {
        val prefix = fuzzyScore("cat", "Catalog")
        val midString = fuzzyScore("cat", "Xcatalog")
        assertNotNull(prefix)
        assertNotNull(midString)
        assertTrue(prefix > midString, "expected prefix match ($prefix) to outscore mid-string match ($midString)")
    }

    @Test
    fun wordStartMatchScoresHigherThanPlainSubsequenceMatch() {
        val wordStart = fuzzyScore("log", "Cata-log")
        val plain = fuzzyScore("log", "Catalog")
        assertNotNull(wordStart)
        assertNotNull(plain)
        assertTrue(wordStart > plain, "expected word-start match ($wordStart) to outscore plain match ($plain)")
    }

    @Test
    fun anchoringRetriesEveryFirstCharOccurrenceSoWordStartAnchorsWin() {
        // a single greedy pass anchors "lev" at the first 'l' it sees (Solo's), scattering the
        // match; multi-start must also try Leveling's 'l' and take that run's higher score
        val leveling = fuzzyScore("lev", "Solo Leveling")
        val television = fuzzyScore("lev", "Television")
        assertNotNull(leveling)
        assertNotNull(television)
        assertTrue(
            leveling > television,
            "expected word-start anchor ($leveling) to outscore mid-word anchor ($television)",
        )
    }

    @Test
    fun repeatedQueryCharactersConsumeDistinctTextCharacters() {
        assertNotNull(fuzzyScore("oo", "Tower of God"))
        assertNull(fuzzyScore("ooo", "Solo"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertNotNull(fuzzyScore("SOLO", "solo leveling"))
        assertNotNull(fuzzyScore("solo", "SOLO LEVELING"))
    }

    @Test
    fun blankQueryMatchesEverythingAtScoreZero() {
        assertEquals0(fuzzyScore("", "anything at all"))
        assertEquals0(fuzzyScore("   ", "anything at all"))
    }

    private fun assertEquals0(score: Int?) {
        assertNotNull(score)
        assertTrue(score == 0, "expected a blank query to score 0, got $score")
    }
}
