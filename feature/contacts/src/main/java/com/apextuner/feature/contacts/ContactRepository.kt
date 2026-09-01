package com.apextuner.feature.contacts

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.apextuner.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface ContactRepository {
    fun hasPermissions(): Boolean
    suspend fun findDuplicates(): List<ContactDuplicateCandidate>
    suspend fun merge(candidate: ContactDuplicateCandidate): ContactMergeUndo
    suspend fun undo(snapshot: ContactMergeUndo)
}

@Singleton
class AndroidContactRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ContactRepository {

    override fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override suspend fun findDuplicates(): List<ContactDuplicateCandidate> = withContext(ioDispatcher) {
        requirePermissions()
        ContactSimilarity.findCandidates(loadContacts())
    }

    override suspend fun merge(candidate: ContactDuplicateCandidate): ContactMergeUndo = withContext(ioDispatcher) {
        requirePermissions()
        require(candidate.first.contactId != candidate.second.contactId) { "These entries already reference the same aggregate contact." }
        val pairs = ContactAggregationPlanner.plan(
            candidate.first.rawContactIds,
            candidate.second.rawContactIds,
        )
        require(pairs.isNotEmpty()) { "Android did not expose writable raw-contact identities for this pair." }

        val snapshots = pairs.map { (first, second) ->
            coroutineContext.ensureActive()
            AggregationRuleSnapshot(first, second, readAggregationType(first, second))
        }
        try {
            applyRules(snapshots, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runCatching { restoreRules(snapshots) }
            throw error
        }
        ContactMergeUndo(candidate.first.displayName, candidate.second.displayName, snapshots)
    }

    override suspend fun undo(snapshot: ContactMergeUndo) = withContext(ioDispatcher) {
        requirePermissions()
        restoreRules(snapshot.rules)
    }

    private fun loadContacts(): List<ContactRecord> {
        val names = LinkedHashMap<Long, String>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext() && names.size < MAX_CONTACTS) {
                val id = cursor.getLong(idIndex)
                names[id] = cursor.getString(nameIndex)?.take(MAX_TEXT) ?: "Unnamed contact"
            }
        }

        data class MutableContact(
            val phones: MutableSet<String> = LinkedHashSet(),
            val emails: MutableSet<String> = LinkedHashSet(),
            val rawIds: MutableSet<Long> = LinkedHashSet(),
        )
        val data = names.keys.associateWith { MutableContact() }.toMutableMap()
        val mimeTypes = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?)"
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.RAW_CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
            ),
            selection,
            mimeTypes,
            null,
        )?.use { cursor ->
            val contactIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val rawIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
            val mimeIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            val valueIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(contactIndex)
                val target = data[contactId] ?: continue
                target.rawIds += cursor.getLong(rawIndex)
                val value = cursor.getString(valueIndex)?.take(MAX_TEXT)?.takeIf { it.isNotBlank() } ?: continue
                when (cursor.getString(mimeIndex)) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (target.phones.size < MAX_VALUES_PER_CONTACT) target.phones += value
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (target.emails.size < MAX_VALUES_PER_CONTACT) target.emails += value
                }
            }
        }

        // Some contacts have raw identities but no phone/email row, so fill raw IDs separately.
        if (data.values.any { it.rawIds.isEmpty() }) {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.CONTACT_ID),
                "${ContactsContract.RawContacts.DELETED}=0",
                null,
                null,
            )?.use { cursor ->
                val rawIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
                val contactIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
                while (cursor.moveToNext()) {
                    data[cursor.getLong(contactIndex)]?.rawIds?.add(cursor.getLong(rawIndex))
                }
            }
        }

        return names.mapNotNull { (id, name) ->
            val values = data[id] ?: return@mapNotNull null
            if (values.phones.isEmpty() && values.emails.isEmpty() && name.isBlank()) return@mapNotNull null
            ContactRecord(id, name, values.phones, values.emails, values.rawIds)
        }
    }

    private fun readAggregationType(first: Long, second: Long): Int? {
        val selection =
            "(${ContactsContract.AggregationExceptions.RAW_CONTACT_ID1}=? AND ${ContactsContract.AggregationExceptions.RAW_CONTACT_ID2}=?) OR " +
                "(${ContactsContract.AggregationExceptions.RAW_CONTACT_ID1}=? AND ${ContactsContract.AggregationExceptions.RAW_CONTACT_ID2}=?)"
        val args = arrayOf(first.toString(), second.toString(), second.toString(), first.toString())
        context.contentResolver.query(
            ContactsContract.AggregationExceptions.CONTENT_URI,
            arrayOf(ContactsContract.AggregationExceptions.TYPE),
            selection,
            args,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return null
    }

    private fun applyRules(rules: List<AggregationRuleSnapshot>, type: Int) {
        rules.chunked(MAX_OPERATIONS_PER_BATCH).forEach { batch ->
            val operations = batch.map { rule ->
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, type)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, rule.rawContactId1)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rule.rawContactId2)
                    .build()
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(operations))
        }
    }

    private fun restoreRules(rules: List<AggregationRuleSnapshot>) {
        val failed = ArrayList<AggregationRuleSnapshot>()
        rules.chunked(MAX_OPERATIONS_PER_BATCH).forEach { batch ->
            val operations = batch.map(::restoreOperation)
            try {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(operations))
            } catch (_: Throwable) {
                // A single stale/deleted raw contact must not prevent independent rules in the
                // same batch from being restored. Retry this batch rule-by-rule and report only
                // the rules Android still refuses.
                batch.forEach { rule ->
                    try {
                        context.contentResolver.applyBatch(
                            ContactsContract.AUTHORITY,
                            arrayListOf(restoreOperation(rule)),
                        )
                    } catch (_: Throwable) {
                        failed += rule
                    }
                }
            }
        }
        check(failed.isEmpty()) {
            "Undo restored ${rules.size - failed.size} of ${rules.size} aggregation rules; ${failed.size} rule(s) could not be restored because Android no longer accepts those raw-contact identities. Retry, or discard this failed undo record to continue to earlier undos."
        }
    }

    private fun restoreOperation(rule: AggregationRuleSnapshot): ContentProviderOperation =
        ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
            .withValue(
                ContactsContract.AggregationExceptions.TYPE,
                rule.previousType ?: ContactsContract.AggregationExceptions.TYPE_AUTOMATIC,
            )
            .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, rule.rawContactId1)
            .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rule.rawContactId2)
            .build()

    private fun requirePermissions() {
        check(hasPermissions()) { "Contacts permission is required for this tool." }
    }

    private companion object {
        const val MAX_CONTACTS = 5_000
        const val MAX_VALUES_PER_CONTACT = 16
        const val MAX_OPERATIONS_PER_BATCH = 64
        const val MAX_TEXT = 512
    }
}

internal object ContactAggregationPlanner {
    private const val MAX_RAW_CONTACT_PAIRS = 512

    fun plan(firstRawIds: Set<Long>, secondRawIds: Set<Long>): List<Pair<Long, Long>> {
        if (firstRawIds.isEmpty() || secondRawIds.isEmpty()) return emptyList()
        val pairCount = firstRawIds.size.toLong() * secondRawIds.size.toLong()
        require(pairCount <= MAX_RAW_CONTACT_PAIRS) {
            "This contact pair expands to $pairCount Android aggregation rules. " +
                "ApexTuner safely supports at most $MAX_RAW_CONTACT_PAIRS in one merge; split the source contacts first."
        }
        return buildList(pairCount.toInt()) {
            firstRawIds.sorted().forEach { first ->
                secondRawIds.sorted().forEach { second ->
                    if (first != second) add(first to second)
                }
            }
        }
    }
}
