package com.apextuner.feature.files

object ZipPathGuard {
    fun safeSegments(entryName: String): List<String>? {
        if (entryName.isBlank() || entryName.length > MAX_ENTRY_NAME) return null
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith('/') || WINDOWS_DRIVE.containsMatchIn(normalized)) return null
        val segments = normalized.split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.size > MAX_DEPTH) return null
        if (segments.any { it == ".." || it.length > MAX_SEGMENT || it.indexOf('\u0000') >= 0 }) return null
        return segments
    }

    fun requireSafe(entryName: String): List<String> =
        requireNotNull(safeSegments(entryName)) { "Unsafe ZIP entry path: $entryName" }

    private const val MAX_ENTRY_NAME = 1_024
    private const val MAX_SEGMENT = 255
    private const val MAX_DEPTH = 64
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:/")
}
