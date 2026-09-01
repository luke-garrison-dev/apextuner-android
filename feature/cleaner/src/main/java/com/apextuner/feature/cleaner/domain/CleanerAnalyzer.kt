package com.apextuner.feature.cleaner.domain

import com.apextuner.feature.cleaner.model.CategoryUsage
import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerCategory
import com.apextuner.feature.cleaner.model.DuplicateGroup

object CleanerAnalyzer {
    const val LARGE_FILE_THRESHOLD_BYTES: Long = 10L * 1024L * 1024L

    fun largeFiles(items: List<CleanableItem>): List<CleanableItem> = preferredPhysicalItems(items)
        .asSequence()
        .filter { it.sizeBytes >= LARGE_FILE_THRESHOLD_BYTES }
        .sortedByDescending { it.sizeBytes }
        .toList()

    fun suspectedJunk(items: List<CleanableItem>): List<CleanableItem> = preferredPhysicalItems(
        items.filter { it.suspectedJunk },
    ).sortedByDescending { it.sizeBytes }

    fun categoryUsage(items: List<CleanableItem>): List<CategoryUsage> {
        val physicalItems = preferredPhysicalItems(items)
        return CleanerCategory.entries
            .mapNotNull { category ->
                val categoryItems = physicalItems.filter { it.category == category }
                if (categoryItems.isEmpty()) null else CategoryUsage(
                    category = category,
                    bytes = categoryItems.sumOfSafe { it.sizeBytes },
                    itemCount = categoryItems.size,
                )
            }
            .sortedByDescending { it.bytes }
    }

    fun totalAccessibleBytes(items: List<CleanableItem>): Long = preferredPhysicalItems(items)
        .sumOfSafe { it.sizeBytes }

    fun potentialReclaimBytes(
        duplicateGroups: List<DuplicateGroup>,
        suspectedJunk: List<CleanableItem>,
    ): Long {
        val candidates = LinkedHashMap<String, Long>()
        fun addCandidate(item: CleanableItem) {
            if (!item.canDelete) return
            val bytes = item.sizeBytes.coerceAtLeast(0L)
            candidates.merge(item.identityKey, bytes) { current, incoming ->
                when {
                    current <= 0L -> incoming
                    incoming <= 0L -> current
                    else -> minOf(current, incoming) // never inflate reclaim due to inconsistent alias metadata
                }
            }
        }
        suspectedJunk.forEach(::addCandidate)
        duplicateGroups.forEach { group ->
            group.items
                .asSequence()
                .filter { it.key != group.recommendedKeepKey }
                .forEach(::addCandidate)
        }
        return candidates.values.sumOfSafe { it }
    }

    private fun preferredPhysicalItems(items: List<CleanableItem>): List<CleanableItem> {
        if (items.size < 2) return items
        val byIdentity = LinkedHashMap<String, CleanableItem>(items.size)
        for (item in items) {
            val current = byIdentity[item.identityKey]
            if (current == null || preferenceScore(item) > preferenceScore(current)) {
                byIdentity[item.identityKey] = item
            }
        }
        return byIdentity.values.toList()
    }

    private fun preferenceScore(item: CleanableItem): Int {
        val deletable = if (item.canDelete) 100 else 0
        val source = when (item.origin) {
            com.apextuner.feature.cleaner.model.CleanerOrigin.MediaStore -> 30
            com.apextuner.feature.cleaner.model.CleanerOrigin.SafTree -> 20
            com.apextuner.feature.cleaner.model.CleanerOrigin.SafDocument -> 20
            com.apextuner.feature.cleaner.model.CleanerOrigin.SelectedMedia -> 10
        }
        return deletable + source
    }


    internal fun isJunkLocation(relativeLocation: String?): Boolean {
        val normalized = relativeLocation
            ?.replace('\\', '/')
            ?.lowercase()
            ?.trim('/')
            ?: return false
        if (normalized.isBlank()) return false
        val segments = normalized.split('/').filter { it.isNotBlank() }.toSet()
        return segments.any { it in JUNK_DIRECTORY_NAMES }
    }

    internal fun classify(displayName: String, mimeType: String?): Pair<CleanerCategory, Boolean> {
        val name = displayName.lowercase()
        val mime = mimeType?.lowercase().orEmpty()
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")

        val category = when {
            mime.startsWith("image/") -> CleanerCategory.Image
            mime.startsWith("video/") -> CleanerCategory.Video
            mime.startsWith("audio/") -> CleanerCategory.Audio
            mime == "application/vnd.android.package-archive" || extension == "apk" -> CleanerCategory.Apk
            mime in DOCUMENT_MIME_TYPES || extension in DOCUMENT_EXTENSIONS -> CleanerCategory.Document
            mime in ARCHIVE_MIME_TYPES || extension in ARCHIVE_EXTENSIONS -> CleanerCategory.Archive
            extension in LOG_EXTENSIONS || name.endsWith(".log.old") -> CleanerCategory.Log
            extension in TEMP_EXTENSIONS || name.endsWith("~") || name.startsWith("._") -> CleanerCategory.Temporary
            else -> CleanerCategory.Other
        }

        val suspected = when (category) {
            CleanerCategory.Temporary, CleanerCategory.Log -> true
            CleanerCategory.Apk -> name.contains("update") || name.contains("installer") || name.contains("download")
            else -> false
        }
        return category to suspected
    }

    private inline fun <T> Iterable<T>.sumOfSafe(selector: (T) -> Long): Long {
        var total = 0L
        for (item in this) total = safeAdd(total, selector(item).coerceAtLeast(0L))
        return total
    }

    private fun safeAdd(a: Long, b: Long): Long = when {
        b <= 0L -> a
        a > Long.MAX_VALUE - b -> Long.MAX_VALUE
        else -> a + b
    }

    private val DOCUMENT_MIME_TYPES = setOf(
        "application/pdf",
        "application/rtf",
        "text/plain",
        "text/csv",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
    private val DOCUMENT_EXTENSIONS = setOf("pdf", "txt", "csv", "rtf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods")
    private val ARCHIVE_MIME_TYPES = setOf("application/zip", "application/x-7z-compressed", "application/x-rar-compressed", "application/gzip")
    private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar", "gz", "tar", "tgz", "bz2", "xz")
    private val LOG_EXTENSIONS = setOf("log", "trace", "stacktrace")
    private val TEMP_EXTENSIONS = setOf("tmp", "temp", "partial", "part", "crdownload", "download")
    private val JUNK_DIRECTORY_NAMES = setOf(".thumbnails", "thumbnails", ".trash", ".trashed", "cache", ".cache", "tmp", "temp")
}
