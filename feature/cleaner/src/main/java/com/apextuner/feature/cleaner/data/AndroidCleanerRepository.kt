package com.apextuner.feature.cleaner.data

import android.Manifest
import android.app.PendingIntent
import android.app.usage.StorageStatsManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.apextuner.core.capability.CapabilityManager
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.model.Capability
import com.apextuner.core.model.CapabilityState
import com.apextuner.feature.cleaner.domain.CleanerAnalyzer
import com.apextuner.feature.cleaner.domain.DuplicateFinder
import com.apextuner.feature.cleaner.domain.HashMode
import com.apextuner.feature.cleaner.domain.LaplacianVariance
import com.apextuner.feature.cleaner.domain.PerceptualDuplicateFinder
import com.apextuner.feature.cleaner.model.CacheInsight
import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerAccessState
import com.apextuner.feature.cleaner.model.CleanerOrigin
import com.apextuner.feature.cleaner.model.CleanerScanLimits
import com.apextuner.feature.cleaner.model.CleanerScanProgress
import com.apextuner.feature.cleaner.model.CleanerScanResult
import com.apextuner.feature.cleaner.model.DeletionOutcome
import com.apextuner.feature.cleaner.model.DuplicateGroup
import com.apextuner.feature.cleaner.model.PersistedAccess
import com.apextuner.feature.cleaner.model.PerceptualDuplicateResult
import com.apextuner.feature.cleaner.model.PerceptualImageMetrics
import com.apextuner.feature.cleaner.model.MediaReencodeEstimate
import com.apextuner.feature.cleaner.model.MediaReencodeMode
import com.apextuner.feature.cleaner.model.MediaReencodeOutcome
import com.apextuner.feature.cleaner.model.MediaReencodePreset
import com.apextuner.feature.cleaner.model.MediaReencodeProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Singleton
class AndroidCleanerRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capabilityManager: CapabilityManager,
    private val mediaReencoder: AndroidMediaReencoder,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CleanerRepository {

    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun accessState(): CleanerAccessState = withContext(ioDispatcher) {
        val legacyRead = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        val visualSelected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val fullImages = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
            else -> legacyRead
        }
        val fullVideos = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
            else -> legacyRead
        }
        val audio = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
            else -> legacyRead
        }
        val persisted = resolver.persistedUriPermissions
        CleanerAccessState(
            canReadImages = fullImages || visualSelected,
            canReadVideos = fullVideos || visualSelected,
            canReadAudio = audio,
            limitedVisualAccess = visualSelected && !(fullImages && fullVideos),
            legacyMediaWriteGranted = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            persistedTrees = persisted.count { DocumentsContract.isTreeUri(it.uri) },
            persistedDocuments = persisted.count { !DocumentsContract.isTreeUri(it.uri) },
            usageAccessGranted = capabilityManager.status(Capability.UsageAccess).state == CapabilityState.Granted,
        )
    }

    override suspend fun persistedAccess(): List<PersistedAccess> = withContext(ioDispatcher) {
        resolver.persistedUriPermissions.map { permission ->
            val uri = permission.uri
            PersistedAccess(
                uri = uri.toString(),
                displayName = resolveDisplayName(uri) ?: uri.lastPathSegment ?: "Document access",
                isTree = DocumentsContract.isTreeUri(uri),
                canWrite = permission.isWritePermission,
            )
        }.sortedWith(compareByDescending<PersistedAccess> { it.isTree }.thenBy { it.displayName.lowercase() })
    }

    override suspend fun persistTree(uri: Uri): Boolean = withContext(ioDispatcher) {
        takePersistablePermission(uri)
    }

    override suspend fun persistDocuments(uris: List<Uri>): PersistGrantOutcome = withContext(ioDispatcher) {
        var granted = 0
        var failed = 0
        uris.distinct().forEach { uri ->
            if (takePersistablePermission(uri)) granted++ else failed++
        }
        PersistGrantOutcome(granted, failed)
    }

    override suspend fun releasePersistedAccess(uri: Uri): Boolean = withContext(ioDispatcher) {
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return@withContext true
        val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            resolver.releasePersistableUriPermission(uri, flags)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    override suspend fun scan(
        onProgress: suspend (CleanerScanProgress) -> Unit,
    ): CleanerScanResult = withContext(ioDispatcher) {
        val access = accessState()
        val items = ArrayList<CleanableItem>(4096)
        val seenKeys = HashSet<String>(4096)
        var skipped = 0
        var truncated = false
        var bytesScanned = 0L
        var currentPhase = CleanerScanProgress.Phase.Discovering

        onProgress(CleanerScanProgress(currentPhase, 0L, 0L))

        suspend fun addItem(item: CleanableItem): Boolean {
            coroutineContext.ensureActive()
            if (!seenKeys.add(item.key)) return true
            if (items.size >= CleanerScanLimits.MAX_ITEMS) {
                truncated = true
                seenKeys.remove(item.key)
                return false
            }
            items += item
            bytesScanned = safeAdd(bytesScanned, item.sizeBytes)
            if (items.size % PROGRESS_ITEM_INTERVAL == 0) {
                onProgress(CleanerScanProgress(currentPhase, items.size.toLong(), bytesScanned))
            }
            return true
        }

        currentPhase = CleanerScanProgress.Phase.Media
        if (access.canReadImages) {
            val result = scanMediaCollection(MediaKind.Images, ::addItem)
            skipped += result.skipped
            truncated = truncated || result.truncated
        }
        if (!truncated && access.canReadVideos) {
            val result = scanMediaCollection(MediaKind.Videos, ::addItem)
            skipped += result.skipped
            truncated = truncated || result.truncated
        }
        if (!truncated && access.canReadAudio) {
            val result = scanMediaCollection(MediaKind.Audio, ::addItem)
            skipped += result.skipped
            truncated = truncated || result.truncated
        }

        currentPhase = CleanerScanProgress.Phase.Documents
        onProgress(CleanerScanProgress(currentPhase, items.size.toLong(), bytesScanned))
        if (!truncated) {
            for (permission in resolver.persistedUriPermissions) {
                coroutineContext.ensureActive()
                val result = when {
                    DocumentsContract.isTreeUri(permission.uri) ->
                        scanTree(permission.uri, permission.isWritePermission, ::addItem)
                    DocumentsContract.isDocumentUri(context, permission.uri) ->
                        scanSingleDocument(permission.uri, permission.isWritePermission, ::addItem)
                    else ->
                        scanSelectedContent(permission.uri, ::addItem)
                }
                skipped += result.skipped
                truncated = truncated || result.truncated
                if (truncated) break
            }
        }

        currentPhase = CleanerScanProgress.Phase.Analyzing
        onProgress(CleanerScanProgress(currentPhase, items.size.toLong(), bytesScanned))
        val large = CleanerAnalyzer.largeFiles(items)
        val junk = CleanerAnalyzer.suspectedJunk(items)
        val categories = CleanerAnalyzer.categoryUsage(items)
        CleanerScanResult(
            items = items,
            duplicateGroups = emptyList(),
            largeFiles = large,
            suspectedJunk = junk,
            categoryUsage = categories,
            totalAccessibleBytes = CleanerAnalyzer.totalAccessibleBytes(items),
            potentialReclaimBytes = CleanerAnalyzer.potentialReclaimBytes(emptyList(), junk),
            skippedItems = skipped,
            truncated = truncated,
            cacheInsight = readCacheInsight(access),
        )
    }

    override suspend fun findDuplicates(
        items: List<CleanableItem>,
        onProgress: suspend (CleanerScanProgress) -> Unit,
    ): List<DuplicateGroup> = withContext(ioDispatcher) {
        var fullHashed = 0L
        val finder = DuplicateFinder { item, mode -> streamDigest(Uri.parse(item.uri), mode) }
        finder.find(items) {
            fullHashed++
            if (fullHashed % HASH_PROGRESS_INTERVAL == 0L) {
                onProgress(
                    CleanerScanProgress(
                        phase = CleanerScanProgress.Phase.Hashing,
                        itemsScanned = fullHashed,
                        bytesScanned = 0L,
                    ),
                )
            }
        }
    }

    override suspend fun findNearDuplicates(
        items: Iterable<CleanableItem>,
        onProgress: suspend (CleanerScanProgress) -> Unit,
    ): PerceptualDuplicateResult = withContext(ioDispatcher) {
        var hashed = 0L
        onProgress(
            CleanerScanProgress(
                phase = CleanerScanProgress.Phase.PerceptualHashing,
                itemsScanned = 0L,
                bytesScanned = 0L,
            ),
        )
        val finder = PerceptualDuplicateFinder(
            analyze = { item -> analyzePerceptualImage(Uri.parse(item.uri)) },
        )
        finder.find(items) {
            hashed++
            if (hashed % HASH_PROGRESS_INTERVAL == 0L) {
                onProgress(
                    CleanerScanProgress(
                        phase = CleanerScanProgress.Phase.PerceptualHashing,
                        itemsScanned = hashed,
                        bytesScanned = 0L,
                    ),
                )
            }
        }
    }

    override suspend fun estimateReencode(
        item: CleanableItem,
        preset: MediaReencodePreset,
    ): MediaReencodeEstimate = withContext(ioDispatcher) {
        mediaReencoder.estimate(item, preset)
    }

    override suspend fun createMediaWriteRequest(item: CleanableItem): PendingIntent? = withContext(ioDispatcher) {
        mediaReencoder.createMediaWriteRequest(item)
    }

    override suspend fun reencode(
        item: CleanableItem,
        preset: MediaReencodePreset,
        mode: MediaReencodeMode,
        onProgress: suspend (MediaReencodeProgress) -> Unit,
    ): MediaReencodeOutcome = withContext(ioDispatcher) {
        mediaReencoder.reencode(item, preset, mode, onProgress)
    }

    override fun createMediaRemovalRequest(
        mediaItems: List<CleanableItem>,
        mode: MediaRemovalMode,
    ): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = mediaItems
            .asSequence()
            .filter { it.origin == CleanerOrigin.MediaStore }
            .map { Uri.parse(it.uri) }
            .distinct()
            .toList()
        if (uris.isEmpty()) return null
        require(uris.size <= MAX_MEDIA_REQUEST_ITEMS) {
            "Android allows at most $MAX_MEDIA_REQUEST_ITEMS media items in one removal request for this target SDK."
        }
        return when (mode) {
            MediaRemovalMode.Trash -> MediaStore.createTrashRequest(resolver, uris, true)
            MediaRemovalMode.Permanent -> MediaStore.createDeleteRequest(resolver, uris)
        }
    }

    override suspend fun deleteNonPromptItems(items: List<CleanableItem>): DeletionOutcome = withContext(ioDispatcher) {
        var deleted = 0
        var failed = 0
        var bytesDeleted = 0L
        val candidates = items.filter {
            it.canDelete && (it.origin != CleanerOrigin.MediaStore || Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
        }
        for (item in candidates) {
            coroutineContext.ensureActive()
            val success = try {
                val uri = Uri.parse(item.uri)
                when (item.origin) {
                    CleanerOrigin.MediaStore -> resolver.delete(uri, null, null) > 0
                    CleanerOrigin.SafTree, CleanerOrigin.SafDocument -> DocumentsContract.deleteDocument(resolver, uri)
                    CleanerOrigin.SelectedMedia -> false
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            if (success) {
                deleted++
                bytesDeleted = safeAdd(bytesDeleted, item.sizeBytes)
            } else {
                failed++
            }
        }
        DeletionOutcome(
            requested = candidates.size,
            deleted = deleted,
            failed = failed,
            bytesDeleted = bytesDeleted,
        )
    }

    override suspend fun evaluateMediaRemoval(items: List<CleanableItem>, mode: MediaRemovalMode): DeletionOutcome = withContext(ioDispatcher) {
        // MediaStore guarantees that an approved createTrashRequest/createDeleteRequest operation
        // has completely finished before RESULT_OK is delivered. Do not infer success by re-querying
        // URIs: trashed rows have special query visibility semantics and deleted rows are absent.
        val media = items.filter { it.origin == CleanerOrigin.MediaStore }.distinctBy { it.uri }
        val bytesChanged = if (mode == MediaRemovalMode.Permanent) {
            media.sumOfSafe { it.sizeBytes }
        } else {
            0L
        }
        DeletionOutcome(
            requested = media.size,
            deleted = media.size,
            failed = 0,
            bytesDeleted = bytesChanged,
        )
    }

    private suspend fun scanMediaCollection(
        kind: MediaKind,
        addItem: suspend (CleanableItem) -> Boolean,
    ): ScanPass = withContext(ioDispatcher) {
        val uri = kind.collectionUri()
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            }
            if (kind != MediaKind.Audio) {
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
            }
            kind.durationColumn()?.let(::add)
        }.toTypedArray()

        var skipped = 0
        var truncated = false
        try {
            resolver.query(uri, projection, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val relativeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val volumeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                val widthIndex = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightIndex = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val durationIndex = kind.durationColumn()?.let(cursor::getColumnIndex) ?: -1
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val id = cursor.getLong(idIndex)
                    val itemUri = Uri.withAppendedPath(uri, id.toString())
                    val name = cursor.getStringOrNull(nameIndex) ?: "Unnamed item"
                    val mime = cursor.getStringOrNull(mimeIndex)
                    val size = cursor.getLongOrZero(sizeIndex)
                    val modifiedSeconds = cursor.getLongOrZero(modifiedIndex)
                    val (category, junk) = CleanerAnalyzer.classify(name, mime)
                    val relativeLocation = cursor.getStringOrNull(relativeIndex)
                    val volumeName = cursor.getStringOrNull(volumeIndex)
                    val mediaKey = "media:$itemUri"
                    val mediaPhysicalKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !volumeName.isNullOrBlank()) {
                        "media-physical:$volumeName:$id"
                    } else {
                        mediaKey
                    }
                    val item = CleanableItem(
                        key = mediaKey,
                        uri = itemUri.toString(),
                        origin = CleanerOrigin.MediaStore,
                        category = category,
                        displayName = name,
                        mimeType = mime,
                        sizeBytes = size,
                        modifiedAtEpochMillis = modifiedSeconds.takeIf { it > 0L }?.let { safeMultiply(it, 1000L) },
                        width = cursor.getIntOrNull(widthIndex),
                        height = cursor.getIntOrNull(heightIndex),
                        durationMillis = cursor.getLongOrNull(durationIndex),
                        relativeLocation = relativeLocation,
                        canDelete = canDeleteMedia(),
                        suspectedJunk = junk || CleanerAnalyzer.isJunkLocation(relativeLocation),
                        physicalKey = mediaPhysicalKey,
                    )
                    if (!addItem(item)) {
                        truncated = true
                        break
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SecurityException) {
            skipped++
        } catch (_: RuntimeException) {
            skipped++
        }
        ScanPass(skipped, truncated)
    }

    private suspend fun scanTree(
        treeUri: Uri,
        canWriteGrant: Boolean,
        addItem: suspend (CleanableItem) -> Boolean,
    ): ScanPass = withContext(ioDispatcher) {
        val treeId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: IllegalArgumentException) {
            return@withContext ScanPass(skipped = 1, truncated = false)
        }
        val queue = ArrayDeque<DirectoryNode>()
        val visitedDirectories = HashSet<String>()
        queue.add(DirectoryNode(documentId = treeId, path = "", metadata = null))
        var skipped = 0
        var truncated = false

        while (queue.isNotEmpty() && !truncated) {
            coroutineContext.ensureActive()
            val node = queue.removeFirst()
            if (!visitedDirectories.add(node.documentId)) continue
            val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId)
            val parentSupportsCreate = canWriteGrant && (
                node.metadata?.flags?.let(::supportsDocumentCreate)
                    ?: documentSupportsCreate(parentDocumentUri)
                )
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.documentId)
            var querySucceeded = false
            var childCount = 0
            try {
                resolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
                    querySucceeded = true
                    val indices = DocumentIndices(cursor)
                    while (cursor.moveToNext()) {
                        coroutineContext.ensureActive()
                        childCount++
                        val documentId = cursor.getStringOrNull(indices.id)
                        if (documentId == null) {
                            skipped++
                            continue
                        }
                        val name = cursor.getStringOrNull(indices.name) ?: "Unnamed item"
                        val mime = cursor.getStringOrNull(indices.mime)
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (visitedDirectories.size + queue.size >= MAX_VISITED_DIRECTORIES) {
                                truncated = true
                                break
                            }
                            queue.addLast(
                                DirectoryNode(
                                    documentId = documentId,
                                    path = buildRelativePath(node.path, name),
                                    metadata = DirectoryMetadata(
                                        uri = documentUri,
                                        name = name,
                                        parentPath = node.path,
                                        modifiedAtEpochMillis = cursor.getLongOrZero(indices.modified).takeIf { it > 0L },
                                        flags = cursor.getLongOrZero(indices.flags),
                                    ),
                                ),
                            )
                            continue
                        }
                        val item = cursor.toCleanableDocument(
                            uri = documentUri,
                            origin = CleanerOrigin.SafTree,
                            canWriteGrant = canWriteGrant,
                            indices = indices,
                            relativeLocation = node.path.ifBlank { null },
                            parentDocumentUri = parentDocumentUri.toString(),
                            canCreateSibling = parentSupportsCreate,
                        )
                        if (item == null) {
                            skipped++
                        } else if (!addItem(item)) {
                            truncated = true
                            break
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                skipped++
            }

            if (!truncated && querySucceeded && childCount == 0 && node.metadata != null) {
                val metadata = node.metadata
                val supportsDelete = metadata.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong() != 0L
                val emptyFolder = CleanableItem(
                    key = documentKey(metadata.uri),
                    uri = metadata.uri.toString(),
                    origin = CleanerOrigin.SafTree,
                    category = com.apextuner.feature.cleaner.model.CleanerCategory.EmptyFolder,
                    displayName = metadata.name,
                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = 0L,
                    modifiedAtEpochMillis = metadata.modifiedAtEpochMillis,
                    relativeLocation = metadata.parentPath.ifBlank { null },
                    canDelete = canWriteGrant && supportsDelete,
                    suspectedJunk = true,
                    physicalKey = physicalKeyForDocument(metadata.uri),
                )
                if (!addItem(emptyFolder)) truncated = true
            }
        }
        ScanPass(skipped, truncated)
    }

    private suspend fun scanSingleDocument(
        uri: Uri,
        canWriteGrant: Boolean,
        addItem: suspend (CleanableItem) -> Boolean,
    ): ScanPass = withContext(ioDispatcher) {
        var skipped = 0
        var truncated = false
        try {
            resolver.query(uri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val indices = DocumentIndices(cursor)
                    val mime = cursor.getStringOrNull(indices.mime)
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                        val item = cursor.toCleanableDocument(uri, CleanerOrigin.SafDocument, canWriteGrant, indices)
                        if (item == null) skipped++ else if (!addItem(item)) truncated = true
                    }
                } else skipped++
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            skipped++
        }
        ScanPass(skipped, truncated)
    }

    private suspend fun scanSelectedContent(
        uri: Uri,
        addItem: suspend (CleanableItem) -> Boolean,
    ): ScanPass = withContext(ioDispatcher) {
        var skipped = 0
        var truncated = false
        try {
            val mime = resolver.getType(uri)
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getStringOrNull(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                        ?: uri.lastPathSegment
                        ?: "Selected media"
                    val size = cursor.getLongOrZero(cursor.getColumnIndex(OpenableColumns.SIZE))
                    val (category, junk) = CleanerAnalyzer.classify(name, mime)
                    val item = CleanableItem(
                        key = "selected:$uri",
                        uri = uri.toString(),
                        origin = CleanerOrigin.SelectedMedia,
                        category = category,
                        displayName = name,
                        mimeType = mime,
                        sizeBytes = size,
                        modifiedAtEpochMillis = null,
                        relativeLocation = "Selected with Android Photo Picker",
                        canDelete = false,
                        suspectedJunk = junk,
                    )
                    if (!addItem(item)) truncated = true
                } else {
                    skipped++
                }
            } ?: run { skipped++ }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            skipped++
        }
        ScanPass(skipped, truncated)
    }

    private fun Cursor.toCleanableDocument(
        uri: Uri,
        origin: CleanerOrigin,
        canWriteGrant: Boolean,
        indices: DocumentIndices,
        relativeLocation: String? = null,
        parentDocumentUri: String? = null,
        canCreateSibling: Boolean = false,
    ): CleanableItem? {
        val name = getStringOrNull(indices.name) ?: return null
        val mime = getStringOrNull(indices.mime)
        val size = getLongOrZero(indices.size)
        val modified = getLongOrZero(indices.modified).takeIf { it > 0L }
        val flags = getLongOrZero(indices.flags)
        val supportsDelete = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong() != 0L
        val supportsWrite = flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE.toLong() != 0L
        val (category, junk) = CleanerAnalyzer.classify(name, mime)
        return CleanableItem(
            key = documentKey(uri),
            uri = uri.toString(),
            origin = origin,
            category = category,
            displayName = name,
            mimeType = mime,
            sizeBytes = size,
            modifiedAtEpochMillis = modified,
            relativeLocation = relativeLocation,
            canDelete = canWriteGrant && supportsDelete,
            canWrite = canWriteGrant && supportsWrite,
            parentDocumentUri = parentDocumentUri,
            canCreateSibling = canCreateSibling,
            suspectedJunk = junk || CleanerAnalyzer.isJunkLocation(relativeLocation),
            physicalKey = physicalKeyForDocument(uri),
        )
    }

    private fun documentSupportsCreate(uri: Uri): Boolean {
        return try {
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    false
                } else {
                    supportsDocumentCreate(
                        cursor.getLongOrZero(
                            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS),
                        ),
                    )
                }
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun supportsDocumentCreate(flags: Long): Boolean =
        flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L

    private fun physicalKeyForDocument(uri: Uri): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val equivalentMedia = try {
                MediaStore.getMediaUri(context, uri)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            if (equivalentMedia != null) {
                val id = equivalentMedia.lastPathSegment?.toLongOrNull()
                val volume = try { MediaStore.getVolumeName(equivalentMedia) } catch (_: Exception) { null }
                if (id != null && !volume.isNullOrBlank()) return "media-physical:$volume:$id"
                return "media:$equivalentMedia"
            }
        }
        return documentKey(uri)
    }

    private suspend fun streamDigest(uri: Uri, mode: HashMode): String? = withContext(ioDispatcher) {
        try {
            resolver.openInputStream(uri)?.use { raw ->
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(raw.buffered(HASH_BUFFER_BYTES), digest).use { input ->
                    val buffer = ByteArray(HASH_BUFFER_BYTES)
                    var remaining = mode.maxBytes ?: Long.MAX_VALUE
                    while (remaining > 0L) {
                        coroutineContext.ensureActive()
                        val requested = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, requested)
                        if (read < 0) break
                        remaining -= read.toLong()
                    }
                }
                digest.digest().toHexString()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun analyzePerceptualImage(uri: Uri): PerceptualImageMetrics? = withContext(ioDispatcher) {
        try {
            coroutineContext.ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return@withContext null

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateImageSampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            coroutineContext.ensureActive()
            val decoded = resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return@withContext null

            try {
                coroutineContext.ensureActive()
                PerceptualImageMetrics(
                    dHash = computeDifferenceHash(decoded),
                    laplacianVariance = computeLaplacianVariance(decoded),
                )
            } finally {
                decoded.recycle()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateImageSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        val longestEdge = maxOf(width, height)
        while (
            sampleSize <= Int.MAX_VALUE / 2 &&
            longestEdge / (sampleSize * 2) >= PERCEPTUAL_DECODE_EDGE
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun computeDifferenceHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            DIFFERENCE_HASH_WIDTH,
            DIFFERENCE_HASH_HEIGHT,
            true,
        )
        return try {
            val pixels = IntArray(DIFFERENCE_HASH_WIDTH * DIFFERENCE_HASH_HEIGHT)
            scaled.getPixels(
                pixels,
                0,
                DIFFERENCE_HASH_WIDTH,
                0,
                0,
                DIFFERENCE_HASH_WIDTH,
                DIFFERENCE_HASH_HEIGHT,
            )
            var result = 0L
            for (y in 0 until DIFFERENCE_HASH_HEIGHT) {
                val row = y * DIFFERENCE_HASH_WIDTH
                for (x in 0 until DIFFERENCE_HASH_COMPARISONS_PER_ROW) {
                    result = result shl 1
                    if (luminance(pixels[row + x]) > luminance(pixels[row + x + 1])) {
                        result = result or 1L
                    }
                }
            }
            result
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun computeLaplacianVariance(bitmap: Bitmap): Double? {
        if (bitmap.width < MIN_SHARPNESS_DIMENSION || bitmap.height < MIN_SHARPNESS_DIMENSION) return null

        val luminanceBuffer = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(
            luminanceBuffer,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height,
        )
        for (index in luminanceBuffer.indices) {
            luminanceBuffer[index] = luminance(luminanceBuffer[index]) / LUMINANCE_SCALE
        }
        return LaplacianVariance.score(
            luminance = luminanceBuffer,
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    private fun luminance(argb: Int): Int {
        val red = argb ushr 16 and 0xff
        val green = argb ushr 8 and 0xff
        val blue = argb and 0xff
        return 299 * red + 587 * green + 114 * blue
    }

    private fun readCacheInsight(access: CleanerAccessState): CacheInsight {
        if (!access.usageAccessGranted) return CacheInsight(bytes = null, available = false)
        return try {
            val manager = context.getSystemService(StorageStatsManager::class.java)
            val stats = manager.queryStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle())
            CacheInsight(bytes = stats.cacheBytes.coerceAtLeast(0L), available = true)
        } catch (_: Exception) {
            CacheInsight(bytes = null, available = false)
        }
    }

    private fun takePersistablePermission(uri: Uri): Boolean {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            resolver.takePersistableUriPermission(uri, readWrite)
            true
        } catch (_: SecurityException) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }

    private fun documentKey(uri: Uri): String = try {
        val documentId = DocumentsContract.getDocumentId(uri)
        "document:${uri.authority.orEmpty()}:$documentId"
    } catch (_: IllegalArgumentException) {
        "document:$uri"
    }

    private fun resolveDisplayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrNull(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun canDeleteMedia(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> true
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> false
        else -> hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun MediaKind.durationColumn(): String? = when (this) {
        MediaKind.Images -> null
        MediaKind.Videos -> MediaStore.Video.VideoColumns.DURATION
        MediaKind.Audio -> MediaStore.Audio.AudioColumns.DURATION
    }

    private fun MediaKind.collectionUri(): Uri = when (this) {
        MediaKind.Images -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaKind.Videos -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaKind.Audio -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private fun ByteArray.toHexString(): String {
        val out = CharArray(size * 2)
        var target = 0
        for (byte in this) {
            val value = byte.toInt() and 0xff
            out[target++] = HEX[value ushr 4]
            out[target++] = HEX[value and 0x0f]
        }
        return String(out)
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (index >= 0 && !isNull(index)) getString(index) else null
    private fun Cursor.getLongOrZero(index: Int): Long = if (index >= 0 && !isNull(index)) getLong(index).coerceAtLeast(0L) else 0L
    private fun Cursor.getLongOrNull(index: Int): Long? = if (index >= 0 && !isNull(index)) getLong(index).takeIf { it >= 0L } else null
    private fun Cursor.getIntOrNull(index: Int): Int? = if (index >= 0 && !isNull(index)) getInt(index).takeIf { it >= 0 } else null

    private inline fun <T> Iterable<T>.sumOfSafe(selector: (T) -> Long): Long {
        var total = 0L
        for (item in this) total = safeAdd(total, selector(item))
        return total
    }

    private fun safeAdd(a: Long, b: Long): Long {
        val positive = b.coerceAtLeast(0L)
        return if (a > Long.MAX_VALUE - positive) Long.MAX_VALUE else a + positive
    }

    private fun safeMultiply(a: Long, b: Long): Long = if (a <= 0L || b <= 0L) 0L else if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b

    private data class ScanPass(val skipped: Int, val truncated: Boolean)

    private data class DirectoryNode(
        val documentId: String,
        val path: String,
        val metadata: DirectoryMetadata?,
    )

    private data class DirectoryMetadata(
        val uri: Uri,
        val name: String,
        val parentPath: String,
        val modifiedAtEpochMillis: Long?,
        val flags: Long,
    )

    private fun buildRelativePath(parent: String, child: String): String {
        val combined = if (parent.isBlank()) child else "$parent/$child"
        return if (combined.length <= MAX_RELATIVE_PATH_CHARS) combined else combined.takeLast(MAX_RELATIVE_PATH_CHARS)
    }

    private enum class MediaKind { Images, Videos, Audio }

    private class DocumentIndices(cursor: Cursor) {
        val id = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val name = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val size = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val modified = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val flags = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
    }

    private companion object {
        const val MAX_VISITED_DIRECTORIES = 20_000
        const val MAX_RELATIVE_PATH_CHARS = 1_024
        const val MAX_MEDIA_REQUEST_ITEMS = 2_000
        const val PROGRESS_ITEM_INTERVAL = 250
        const val HASH_PROGRESS_INTERVAL = 5L
        const val HASH_BUFFER_BYTES = 128 * 1024
        const val PERCEPTUAL_DECODE_EDGE = 128
        const val DIFFERENCE_HASH_WIDTH = 9
        const val DIFFERENCE_HASH_HEIGHT = 8
        const val DIFFERENCE_HASH_COMPARISONS_PER_ROW = DIFFERENCE_HASH_WIDTH - 1
        const val LUMINANCE_SCALE = 1_000
        const val MIN_SHARPNESS_DIMENSION = 3
        val HEX = "0123456789abcdef".toCharArray()

        val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
