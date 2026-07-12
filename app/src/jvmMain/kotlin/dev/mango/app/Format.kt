package dev.mango.app

import kotlin.time.Instant

/** "8.0" reads as a float; chapters are "8" or "8.5". Shared by DetailsScreen and DownloadsScreen. */
internal fun formatChapterNumber(number: Double): String =
    if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()

/** Raw Instant.toString() is machine format; readers get the date only. */
internal fun formatDate(instant: Instant): String = instant.toString().substringBefore('T')
