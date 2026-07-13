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
}
