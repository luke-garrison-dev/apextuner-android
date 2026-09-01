package com.apextuner.feature.contacts

/**
 * In-memory, screen-scoped undo history. Contact changes themselves live in Android's provider;
 * this class only tracks which snapshots ApexTuner can still attempt to restore.
 */
internal class ContactUndoHistory {
    private val stack = ArrayDeque<ContactMergeUndo>()
    private val failed = mutableSetOf<ContactMergeUndo>()

    val hasUndo: Boolean get() = stack.isNotEmpty()
    val topFailed: Boolean get() = stack.lastOrNull() in failed
    fun peek(): ContactMergeUndo? = stack.lastOrNull()

    fun push(snapshot: ContactMergeUndo) {
        stack.add(snapshot)
    }

    fun markFailed(snapshot: ContactMergeUndo) {
        if (stack.lastOrNull() == snapshot) failed += snapshot
    }

    fun complete(snapshot: ContactMergeUndo): Boolean {
        if (stack.lastOrNull() != snapshot) return false
        stack.removeLast()
        failed.remove(snapshot)
        return true
    }

    fun discardFailedTop(): ContactMergeUndo? {
        val snapshot = stack.lastOrNull() ?: return null
        if (snapshot !in failed) return null
        stack.removeLast()
        failed.remove(snapshot)
        return snapshot
    }

    fun clear() {
        stack.clear()
        failed.clear()
    }
}
