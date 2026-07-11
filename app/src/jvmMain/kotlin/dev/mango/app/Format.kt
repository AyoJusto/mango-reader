package dev.mango.app

/** "8.0" reads as a float; chapters are "8" or "8.5". Shared by DetailsScreen and DownloadsScreen. */
internal fun formatChapterNumber(number: Double): String =
    if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()
