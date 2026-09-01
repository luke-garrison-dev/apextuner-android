package com.apextuner.core.backup

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import com.apextuner.core.datastore.PreferencesRepository
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.model.AppPreferences
import com.apextuner.core.model.MaintenanceCadence
import com.apextuner.core.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

@Singleton
class BackupRestoreManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun write(uri: Uri): BackupWriteResult = withContext(io) {
        writePayload(uri, System.currentTimeMillis())
    }

    suspend fun writeScheduled(
        treeUri: Uri,
        retentionCount: Int,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): ScheduledBackupWriteResult = withContext(io) {
        require(retentionCount in MIN_RETENTION..MAX_RETENTION)
        require(hasPersistedWriteGrant(treeUri)) { "The scheduled backup folder write grant is no longer available." }
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val displayName = scheduledDisplayName(nowEpochMillis)
        val created = DocumentsContract.createDocument(
            resolver,
            rootDocument,
            "application/json",
            displayName,
        ) ?: error("Android could not create the scheduled backup document.")

        try {
            val write = writePayload(created, nowEpochMillis)
            coroutineContext.ensureActive()
            pruneScheduledBackups(treeUri, rootId, retentionCount)
            val retained = listScheduledBackups(treeUri, rootId).count { BackupRetentionPolicy.isManagedBackup(it.displayName) }
            ScheduledBackupWriteResult(displayName, write.bytesWritten, retained)
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw error
        }
    }

    suspend fun read(uri: Uri): BackupReadResult = withContext(io) {
        try {
            val text = readBounded(uri)
            val json = JSONObject(text)
            val version = json.optInt("schemaVersion", -1)
            if (version != SCHEMA_VERSION) return@withContext BackupReadResult.Invalid("Unsupported ApexTuner backup schema: $version.")
            val sourcePackage = json.optString("sourcePackage", "")
            if (sourcePackage != context.packageName) return@withContext BackupReadResult.Invalid("This backup belongs to a different ApexTuner application ID/build.")
            val prefsJson = json.optJSONObject("preferences") ?: return@withContext BackupReadResult.Invalid("The backup has no preferences section.")
            val prefs = parsePreferences(prefsJson) ?: return@withContext BackupReadResult.Invalid("The backup contains invalid preference values.")
            val apps = json.optJSONArray("visibleApps")
            val count = apps?.length()?.coerceIn(0, MAX_APP_RECORDS) ?: 0
            BackupReadResult.Valid(
                BackupPreview(
                    schemaVersion = version,
                    createdAtEpochMillis = json.optLong("createdAtEpochMillis", 0L).coerceAtLeast(0L),
                    sourcePackage = sourcePackage,
                    sourceVersion = json.optString("sourceVersion", "Unknown").take(80),
                    visibleAppCount = count,
                    preferences = prefs,
                    warnings = listOf(
                        "Installed-app data, APKs, home-screen layout, purchases, VPN state and root/Shizuku authorization are intentionally not restored.",
                        "Any currently active system profile or automated night-profile ownership is never imported.",
                        "Scheduled-backup folder grants and enabled state are never imported.",
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: JSONException) {
            BackupReadResult.Invalid("The selected file is not valid ApexTuner JSON.")
        } catch (tooLarge: BackupTooLargeException) {
            BackupReadResult.Invalid(tooLarge.message ?: "The backup is too large.")
        } catch (_: Throwable) {
            BackupReadResult.Invalid("The selected backup could not be read safely.")
        }
    }

    suspend fun apply(uri: Uri): BackupReadResult = withContext(io) {
        when (val parsed = read(uri)) {
            is BackupReadResult.Invalid -> parsed
            is BackupReadResult.Valid -> {
                preferencesRepository.restoreUserPreferences(parsed.preview.preferences)
                parsed
            }
        }
    }

    private suspend fun writePayload(uri: Uri, createdAtEpochMillis: Long): BackupWriteResult {
        coroutineContext.ensureActive()
        val prefs = preferencesRepository.preferences.first()
        val apps = visibleApps()
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("createdAtEpochMillis", createdAtEpochMillis)
            .put("sourcePackage", context.packageName)
            .put("sourceVersion", appVersion())
            .put("privacy", "Local export. No entitlement, keystore secret, file history, notification history, firewall payload, privileged authorization, or SAF folder grant is included.")
            .put("preferences", preferencesToJson(prefs))
            .put("visibleApps", JSONArray().apply {
                apps.forEach { (packageName, label) ->
                    put(JSONObject().put("packageName", packageName).put("label", label))
                }
            })
        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { "Backup is unexpectedly large." }
        coroutineContext.ensureActive()
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            var offset = 0
            while (offset < bytes.size) {
                coroutineContext.ensureActive()
                val length = minOf(16 * 1024, bytes.size - offset)
                output.write(bytes, offset, length)
                offset += length
            }
            output.flush()
        } ?: error("Android could not open the selected destination.")
        return BackupWriteResult(apps.size, bytes.size)
    }

    private suspend fun pruneScheduledBackups(treeUri: Uri, rootId: String, retentionCount: Int) {
        val resolver = context.contentResolver
        BackupRetentionPolicy.entriesToDelete(listScheduledBackups(treeUri, rootId), retentionCount).forEach { entry ->
            coroutineContext.ensureActive()
            val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId)
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
        }
    }

    private fun listScheduledBackups(treeUri: Uri, rootId: String): List<BackupFileEntry> {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return buildList {
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val modifiedColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeColumn).orEmpty()
                    val name = cursor.getString(nameColumn).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR || !BackupRetentionPolicy.isManagedBackup(name)) continue
                    add(
                        BackupFileEntry(
                            documentId = cursor.getString(idColumn).orEmpty(),
                            displayName = name,
                            lastModifiedEpochMillis = cursor.getLong(modifiedColumn).coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }
    }

    private fun hasPersistedWriteGrant(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isWritePermission
        }

    private fun scheduledDisplayName(nowEpochMillis: Long): String =
        "ApexTuner-backup-v1-${SCHEDULED_NAME_FORMAT.format(Instant.ofEpochMilli(nowEpochMillis))}.json"

    private fun visibleApps(): List<Pair<String, String>> {
        val launcher = context.getSystemService(LauncherApps::class.java)
        return runCatching {
            launcher.getActivityList(null, Process.myUserHandle())
                .asSequence()
                .map { it.applicationInfo.packageName to it.label.toString().take(120) }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
                .take(MAX_APP_RECORDS)
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun preferencesToJson(prefs: AppPreferences) = JSONObject()
        .put("themeMode", prefs.themeMode.name)
        .put("dynamicColor", prefs.dynamicColor)
        .put("hapticsEnabled", prefs.hapticsEnabled)
        .put("showAdvancedTools", prefs.showAdvancedTools)
        .put("telemetryRefreshMillis", prefs.telemetryRefreshMillis)
        .put("scheduledMaintenanceEnabled", prefs.scheduledMaintenanceEnabled)
        .put("maintenanceCadence", prefs.maintenanceCadence.name)
        .put("nightBatteryProfileEnabled", prefs.nightBatteryProfileEnabled)
        .put("scheduledBackupCadence", prefs.scheduledBackupCadence.name)
        .put("scheduledBackupRetentionCount", prefs.scheduledBackupRetentionCount.coerceIn(MIN_RETENTION, MAX_RETENTION))

    private fun parsePreferences(json: JSONObject): AppPreferences? {
        val theme = ThemeMode.entries.firstOrNull { it.name == json.optString("themeMode") } ?: return null
        val cadence = MaintenanceCadence.entries.firstOrNull { it.name == json.optString("maintenanceCadence") } ?: return null
        val backupCadence = MaintenanceCadence.entries.firstOrNull {
            it.name == json.optString("scheduledBackupCadence", MaintenanceCadence.Weekly.name)
        } ?: return null
        val refresh = json.optLong("telemetryRefreshMillis", 2_000L)
        val retention = json.optInt("scheduledBackupRetentionCount", 5)
        if (refresh !in 1_000L..60_000L || retention !in MIN_RETENTION..MAX_RETENTION) return null
        return AppPreferences(
            themeMode = theme,
            dynamicColor = json.optBoolean("dynamicColor", false),
            hapticsEnabled = json.optBoolean("hapticsEnabled", true),
            showAdvancedTools = json.optBoolean("showAdvancedTools", false),
            telemetryRefreshMillis = refresh,
            scheduledMaintenanceEnabled = json.optBoolean("scheduledMaintenanceEnabled", false),
            maintenanceCadence = cadence,
            nightBatteryProfileEnabled = json.optBoolean("nightBatteryProfileEnabled", false),
            nightBatteryProfileAppliedByAutomation = false,
            scheduledBackupEnabled = false,
            scheduledBackupCadence = backupCadence,
            scheduledBackupRetentionCount = retention,
            scheduledBackupTreeUri = null,
            systemProfileBackup = null,
        )
    }

    private suspend fun readBounded(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: error("Android could not open the selected file.")
        input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                coroutineContext.ensureActive()
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BACKUP_BYTES) throw BackupTooLargeException("Backup exceeds the ${MAX_BACKUP_BYTES / 1024} KiB safety limit.")
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "Unknown"
    }.getOrDefault("Unknown")

    private class BackupTooLargeException(message: String) : IllegalArgumentException(message)

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_BACKUP_BYTES = 2 * 1024 * 1024
        const val MAX_APP_RECORDS = 5_000
        const val MIN_RETENTION = 1
        const val MAX_RETENTION = 30
        val SCHEDULED_NAME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
