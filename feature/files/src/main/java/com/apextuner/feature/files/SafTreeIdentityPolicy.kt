package com.apextuner.feature.files

internal data class SafTreeIdentity(
    val authority: String,
    val documentId: String,
)

/** Compares persisted SAF grants without conflating different folders from one provider. */
internal object SafTreeIdentityPolicy {
    fun same(first: SafTreeIdentity, second: SafTreeIdentity): Boolean =
        first.authority == second.authority && first.documentId == second.documentId
}
