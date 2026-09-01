package com.apextuner.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZipPathGuardTest {
    @Test
    fun acceptsRelativeNestedEntry() {
        assertEquals(listOf("photos", "2026", "image.jpg"), ZipPathGuard.safeSegments("photos/2026/image.jpg"))
    }

    @Test
    fun rejectsTraversalAndAbsolutePaths() {
        val unsafe = listOf(
            "../secret",
            "safe/../../secret",
            "/etc/passwd",
            "C:/Windows/System32/file",
            "safe\\..\\secret",
        )
        unsafe.forEach { assertNull(it, ZipPathGuard.safeSegments(it)) }
    }

    @Test
    fun removesHarmlessDotSegments() {
        assertEquals(listOf("safe", "file.txt"), ZipPathGuard.safeSegments("./safe/./file.txt"))
    }
}
