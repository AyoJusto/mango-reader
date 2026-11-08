package dev.mango.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class FormatTest {
    private val now = Instant.fromEpochMilliseconds(1_000_000_000L)

    @Test
    fun under60SecondsReadsAsJustNow() {
        assertEquals("just now", formatRelativeTime(now - 59.seconds, now))
    }

    @Test
    fun exactly60SecondsReadsAsOneMinuteAgo() {
        assertEquals("1 min ago", formatRelativeTime(now - 60.seconds, now))
    }

    @Test
    fun hoursFormatAsHAgo() {
        assertEquals("3 h ago", formatRelativeTime(now - 3.hours, now))
    }

    @Test
    fun daysFormatAsDAgo() {
        assertEquals("2 d ago", formatRelativeTime(now - 2.days, now))
    }

    @Test
    fun aFutureInstantClampsToJustNow() {
        assertEquals("just now", formatRelativeTime(now + 5.minutes, now))
    }

    @Test
    fun nullOrBlankTitleIsNotReal() {
        assertEquals(null, realChapterTitle(8.0, null))
        assertEquals(null, realChapterTitle(8.0, "  "))
    }

    @Test
    fun titlesThatRestateTheNumberAreNotReal() {
        listOf("Chapter 8", "chapter 8", "Ch. 8", "ch 8", "CH.8", "Episode 8", "Ep. 8", "8", "Chapter 08", " Chapter 8 ")
            .forEach { assertEquals(null, realChapterTitle(8.0, it), it) }
    }

    @Test
    fun decimalNumbersMatchTheirOwnRestatement() {
        assertEquals(null, realChapterTitle(8.5, "Chapter 8.5"))
        assertEquals("Chapter 8.5", realChapterTitle(8.0, "Chapter 8.5"))
    }

    @Test
    fun aDifferentNumberIsReal() {
        assertEquals("Chapter 9", realChapterTitle(8.0, "Chapter 9"))
    }

    @Test
    fun aTitleContainingTheNumberIsReal() {
        assertEquals("Chapter 8: The Return", realChapterTitle(8.0, "Chapter 8: The Return"))
        assertEquals("The Monarch's Return", realChapterTitle(8.0, "The Monarch's Return"))
    }
}
