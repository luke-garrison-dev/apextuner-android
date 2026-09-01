package com.apextuner.feature.notifications

import java.util.concurrent.TimeUnit

data class NotificationHistoryAvailability(
    val available: Boolean,
    val reason: String? = null,
)

data class NotificationHistorySettings(
    val enabled: Boolean = false,
    val retentionDays: Int = NotificationHistoryPolicy.DEFAULT_RETENTION_DAYS,
    val mutedPackages: Set<String> = emptySet(),
)

data class NotificationHistoryItem(
    val id: Long,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAtEpochMillis: Long,
)

data class NotificationCapture(
    val packageName: String,
    val title: String,
    val text: String,
    val postedAtEpochMillis: Long,
)


data class NotificationAppCount(val packageName: String, val count: Int)

data class NotificationIntelligence(
    val sampleCount: Int,
    val uniqueApps: Int,
    val topApps: List<NotificationAppCount>,
    val busiestHour: Int?,
    val increasingPackage: String?,
    val increasingDelta: Int?,
    val sampleLimitReached: Boolean,
)

fun analyzeNotificationHistory(items: List<NotificationHistoryItem>): NotificationIntelligence {
    if (items.isEmpty()) return NotificationIntelligence(0, 0, emptyList(), null, null, null, false)
    val counts = items.groupingBy { it.packageName }.eachCount()
    val top = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(5)
        .map { NotificationAppCount(it.key, it.value) }
    val zone = java.time.ZoneId.systemDefault()
    val hourCounts = items.groupingBy { item ->
        java.time.Instant.ofEpochMilli(item.postedAtEpochMillis).atZone(zone).hour
    }.eachCount()
    val busiestHour = hourCounts.maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { -it.key })?.key

    val newest = items.maxOf { it.postedAtEpochMillis }
    val oldest = items.minOf { it.postedAtEpochMillis }
    val span = (newest - oldest).coerceAtLeast(0L)
    val midpoint = oldest + span / 2L
    val trend = if (items.size >= 20 && span >= 6L * 60L * 60L * 1000L) {
        val older = items.filter { it.postedAtEpochMillis < midpoint }.groupingBy { it.packageName }.eachCount()
        val newer = items.filter { it.postedAtEpochMillis >= midpoint }.groupingBy { it.packageName }.eachCount()
        (older.keys + newer.keys).map { pkg -> pkg to ((newer[pkg] ?: 0) - (older[pkg] ?: 0)) }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
    } else null

    return NotificationIntelligence(
        sampleCount = items.size,
        uniqueApps = counts.size,
        topApps = top,
        busiestHour = busiestHour,
        increasingPackage = trend?.first,
        increasingDelta = trend?.second,
        sampleLimitReached = items.size >= NotificationHistoryPolicy.MAX_VISIBLE_ITEMS,
    )
}

object NotificationHistoryPolicy {
    val allowedRetentionDays: Set<Int> = linkedSetOf(1, 3, 7, 14, 30)

    const val DEFAULT_RETENTION_DAYS = 7
    const val MAX_HISTORY_ITEMS = 25_000
    const val MAX_VISIBLE_ITEMS = 500
    const val MAX_PACKAGE_NAME_CHARS = 255
    const val MAX_TITLE_CHARS = 256
    const val MAX_TEXT_CHARS = 2_048

    fun normalizeRetentionDays(days: Int): Int =
        days.takeIf(allowedRetentionDays::contains) ?: DEFAULT_RETENTION_DAYS

    fun retentionCutoffEpochMillis(nowEpochMillis: Long, retentionDays: Int): Long {
        val safeNow = nowEpochMillis.coerceAtLeast(0L)
        val duration = TimeUnit.DAYS.toMillis(normalizeRetentionDays(retentionDays).toLong())
        return (safeNow - duration).coerceAtLeast(0L)
    }

    fun sanitizePackageName(packageName: String): String? {
        val candidate = packageName.trim()
        if (candidate.isEmpty() || candidate.length > MAX_PACKAGE_NAME_CHARS) return null
        if (candidate.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_') }) return null
        if (candidate == "android") return candidate
        if (candidate.startsWith('.') || candidate.endsWith('.') || ".." in candidate || '.' !in candidate) return null
        val hasInvalidSegment = candidate.split('.').any { segment ->
            segment.isEmpty() || !(segment.first() in 'a'..'z' || segment.first() in 'A'..'Z')
        }
        if (hasInvalidSegment) return null
        return candidate
    }

    fun sanitizeTitle(value: CharSequence?): String =
        sanitizeText(value, MAX_TITLE_CHARS)

    fun sanitizeBody(value: CharSequence?): String =
        sanitizeText(value, MAX_TEXT_CHARS)

    fun sanitizeMutedPackages(packages: Set<String>, ownPackageName: String): Set<String> =
        packages.asSequence()
            .mapNotNull(::sanitizePackageName)
            .filterNot { it == ownPackageName }
            .distinct()
            .sorted()
            .take(MAX_MUTED_PACKAGES)
            .toSet()

    fun collectionAllowed(
        settings: NotificationHistorySettings,
        premium: Boolean,
        notificationAccessGranted: Boolean,
    ): Boolean = settings.enabled && premium && notificationAccessGranted

    private fun sanitizeText(value: CharSequence?, maxChars: Int): String {
        if (value == null) return ""
        return value.subSequence(0, minOf(value.length, maxChars))
            .toString()
            .replace('\u0000', ' ')
            .trim()
    }

    private const val MAX_MUTED_PACKAGES = 2_000
}
