package dev.mango.app

import kotlin.time.Instant

/** "8.0" reads as a float; chapters are "8" or "8.5". Shared by DetailsScreen and DownloadsScreen. */
internal fun formatChapterNumber(number: Double): String =
    if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()

/** The title when it says more than the chapter number; sources often fill the title with "Chapter 8" for chapter 8. */
internal fun realChapterTitle(number: Double, title: String?): String? {
    val trimmed = title?.trim()
    if (trimmed.isNullOrEmpty()) return null
    val restatement = Regex(
        "^(ch(apter)?|ep(isode)?)?\\.?\\s*0*${Regex.escape(formatChapterNumber(number))}$",
        RegexOption.IGNORE_CASE,
    )
    return if (restatement.matches(trimmed)) null else trimmed
}

/** Raw Instant.toString() is machine format; readers get the date only. */
internal fun formatDate(instant: Instant): String = instant.toString().substringBefore('T')

/** "just now" under a minute, then "N min ago" / "N h ago" / "N d ago". */
internal fun formatRelativeTime(then: Instant, now: Instant): String {
    val seconds = (now - then).inWholeSeconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86400} d ago"
    }
}
