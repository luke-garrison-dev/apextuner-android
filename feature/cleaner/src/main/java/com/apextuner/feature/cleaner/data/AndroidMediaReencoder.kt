package com.apextuner.feature.cleaner.data

import android.Manifest
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.apextuner.feature.cleaner.domain.MediaReencodeEstimator
import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerCategory
import com.apextuner.feature.cleaner.model.CleanerOrigin
import com.apextuner.feature.cleaner.model.MediaReencodeEstimate
import com.apextuner.feature.cleaner.model.MediaReencodeMode
import com.apextuner.feature.cleaner.model.MediaReencodeOutcome
import com.apextuner.feature.cleaner.model.MediaReencodePreset
import com.apextuner.feature.cleaner.model.MediaReencodeProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Singleton
class AndroidMediaReencoder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver get() = context.contentResolver
    private val tempRoot: File
        get() = (context.externalCacheDir ?: context.cacheDir).resolve(TEMP_DIRECTORY_NAME)

    suspend fun estimate(
        item: CleanableItem,
        preset: MediaReencodePreset,
    ): MediaReencodeEstimate {
        coroutineContext.ensureActive()
        val enriched = probeMissingMetadata(item)
        val base = MediaReencodeEstimator.estimate(enriched, preset)
        if (!base.supported) return base

        if (enriched.category == CleanerCategory.Video) {
            val capabilityIssue = VideoTranscoder(resolver).capabilityIssue(
                sourceUri = Uri.parse(enriched.uri),
                requestedWidth = base.targetWidth,
                requestedHeight = base.targetHeight,
                preset = preset,
            )
            if (capabilityIssue != null) {
                return base.copy(
                    copyAvailable = false,
                    copyUnavailableReason = capabilityIssue,
                    replaceAvailable = false,
                    replaceUnavailableReason = capabilityIssue,
                    supported = false,
                    unavailableReason = capabilityIssue,
                )
            }
        }

        val stageRequired = safeAdd(base.estimatedOutputBytes, TEMP_RESERVE_BYTES)
        val tempUsable = tempRoot.parentFile?.usableSpace ?: context.cacheDir.usableSpace
        val copyDestinationAvailable = canCreateSafSibling(enriched) || canPublishMediaStoreCopy()
        val copySpaceAvailable = tempUsable >= stageRequired
        val copyAvailable = copyDestinationAvailable && copySpaceAvailable
        val copyReason = when {
            !copyDestinationAvailable ->
                "No writable SAF destination is available, and this Android version cannot create a MediaStore copy with the current storage grant."
            !copySpaceAvailable ->
                "There is not enough temporary app storage to stage the compressed copy safely."
            else -> null
        }

        val replaceAccess = canReplaceOriginal(enriched)
        val replaceContainerSafe = base.replaceAvailable
        val rollbackRequired = safeAdd(
            safeAdd(enriched.sizeBytes.coerceAtLeast(0L), base.estimatedOutputBytes),
            TEMP_RESERVE_BYTES,
        )
        val rollbackSpaceAvailable = tempUsable >= rollbackRequired
        val replaceAvailable = replaceContainerSafe && replaceAccess && rollbackSpaceAvailable
        val replaceReason = when {
            !replaceContainerSafe -> base.replaceUnavailableReason
            !replaceAccess -> replaceAccessReason(enriched)
            !rollbackSpaceAvailable ->
                "There is not enough temporary app storage to snapshot the original before replacement."
            else -> null
        }

        return base.copy(
            copyAvailable = copyAvailable,
            copyUnavailableReason = copyReason,
            replaceAvailable = replaceAvailable,
            replaceUnavailableReason = replaceReason,
            supported = copyAvailable || replaceAvailable,
            unavailableReason = if (copyAvailable || replaceAvailable) null else {
                copyReason ?: replaceReason ?: "No safe output destination is available."
            },
        )
    }

    suspend fun createMediaWriteRequest(item: CleanableItem): PendingIntent? {
        if (item.origin != CleanerOrigin.MediaStore) return null
        val uri = Uri.parse(item.uri)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                MediaStore.createWriteRequest(resolver, mutableListOf(uri))
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                try {
                    resolver.openFileDescriptor(uri, "rw")?.use { }
                    null
                } catch (security: RecoverableSecurityException) {
                    security.userAction.actionIntent
                }
            }
            else -> null
        }
    }

    suspend fun reencode(
        item: CleanableItem,
        preset: MediaReencodePreset,
        mode: MediaReencodeMode,
        onProgress: suspend (MediaReencodeProgress) -> Unit,
    ): MediaReencodeOutcome {
        val estimate = estimate(item, preset)
        require(estimate.supported) {
            estimate.unavailableReason ?: "This item cannot be safely re-encoded."
        }
        when (mode) {
            MediaReencodeMode.SaveAsCopy -> require(estimate.copyAvailable) {
                estimate.copyUnavailableReason ?: "A safe copy destination is unavailable."
            }
            MediaReencodeMode.ReplaceOriginal -> require(estimate.replaceAvailable) {
                estimate.replaceUnavailableReason ?: "Safe replacement is unavailable."
            }
        }

        val sourceUri = Uri.parse(item.uri)
        val directory = tempRoot.apply { mkdirs() }
        check(directory.isDirectory) { "ApexTuner could not create its temporary re-encode directory." }
        val staged = File(
            directory,
            "stage-${UUID.randomUUID()}.${estimate.outputExtension}",
        )
        try {
            onProgress(MediaReencodeProgress(MediaReencodeProgress.Phase.Preparing, 0f))
            coroutineContext.ensureActive()
            when (item.category) {
                CleanerCategory.Image -> transcodeImage(
                    sourceUri = sourceUri,
                    destination = staged,
                    estimate = estimate,
                    preset = preset,
                    onProgress = onProgress,
                )
                CleanerCategory.Video -> VideoTranscoder(resolver).transcode(
                    sourceUri = sourceUri,
                    destination = staged,
                    requestedWidth = estimate.targetWidth,
                    requestedHeight = estimate.targetHeight,
                    preset = preset,
                    durationMillis = requireNotNull(probeMissingMetadata(item).durationMillis) {
                        "Video duration became unavailable before transcoding."
                    },
                    onProgress = { fraction ->
                        onProgress(
                            MediaReencodeProgress(
                                phase = MediaReencodeProgress.Phase.Transcoding,
                                fraction = fraction,
                            ),
                        )
                    },
                )
                else -> error("Only images and videos can be re-encoded.")
            }
            coroutineContext.ensureActive()
            syncFile(staged)
            coroutineContext.ensureActive()
            val stagedBytes = staged.length().coerceAtLeast(0L)
            check(stagedBytes > 0L) { "The encoder did not produce a valid output file." }
            check(stagedBytes < item.sizeBytes) {
                "The re-encoded file was not smaller than the original, so ApexTuner kept the original unchanged."
            }

            val outputUri = when (mode) {
                MediaReencodeMode.SaveAsCopy -> publishCopy(
                    item = item,
                    staged = staged,
                    estimate = estimate,
                    onProgress = onProgress,
                )
                MediaReencodeMode.ReplaceOriginal -> replaceOriginal(
                    item = item,
                    staged = staged,
                    onProgress = onProgress,
                )
            }
            return MediaReencodeOutcome(
                mode = mode,
                outputUri = outputUri.toString(),
                sourceBytes = item.sizeBytes,
                outputBytes = stagedBytes,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            runCatching { staged.delete() }
        }
    }

    private suspend fun transcodeImage(
        sourceUri: Uri,
        destination: File,
        estimate: MediaReencodeEstimate,
        preset: MediaReencodePreset,
        onProgress: suspend (MediaReencodeProgress) -> Unit,
    ) {
        coroutineContext.ensureActive()
        val orientation = readExifOrientation(sourceUri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: error("Android could not read the source image.")
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "The source image dimensions are invalid." }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateDecodeSampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                targetWidth = estimate.targetWidth,
                targetHeight = estimate.targetHeight,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: error("Android could not decode the source image.")

        try {
            coroutineContext.ensureActive()
            val oriented = applyExifOrientation(decoded, orientation)
            try {
                val (targetWidth, targetHeight) = MediaReencodeEstimator.fitWithin(
                    oriented.width,
                    oriented.height,
                    preset.imageMaxLongEdge,
                )
                val scaled = if (oriented.width == targetWidth && oriented.height == targetHeight) {
                    oriented
                } else {
                    Bitmap.createScaledBitmap(oriented, targetWidth, targetHeight, true)
                }
                try {
                    onProgress(MediaReencodeProgress(MediaReencodeProgress.Phase.Transcoding, 0.55f))
                    coroutineContext.ensureActive()
                    val format = compressFormat(estimate.outputMimeType)
                    val job = coroutineContext[Job]
                    FileOutputStream(destination).buffered(IMAGE_OUTPUT_BUFFER_BYTES).use { raw ->
                        CancellationCheckingOutputStream(raw) { job?.ensureActive() }.use { output ->
                            check(scaled.compress(format, preset.imageQuality, output)) {
                                "Android could not encode the image."
                            }
                            output.flush()
                        }
                    }
                    coroutineContext.ensureActive()
                    onProgress(MediaReencodeProgress(MediaReencodeProgress.Phase.Transcoding, 1f))
                } finally {
                    if (scaled !== oriented) scaled.recycle()
                }
            } finally {
                if (oriented !== decoded) oriented.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    private suspend fun publishCopy(
        item: CleanableItem,
        staged: File,
        estimate: MediaReencodeEstimate,
        onProgress: suspend (MediaReencodeProgress) -> Unit,
    ): Uri {
        onProgress(MediaReencodeProgress(MediaReencodeProgress.Phase.SavingCopy, 0f))
        coroutineContext.ensureActive()
        val name = compressedName(item.displayName, estimate.outputExtension)
        val uri = if (canCreateSafSibling(item)) {
            publishSafSibling(
                parentUri = Uri.parse(requireNotNull(item.parentDocumentUri)),
                mimeType = estimate.outputMimeType,
                displayName = name,
                staged = staged,
                onProgress = onProgress,
            )
        } else {
            publishMediaStoreCopy(
                item = item,
                mimeType = estimate.outputMimeType,
                displayName = name,
                staged = staged,
                onProgress = onProgress,
            )
        }
        onProgress(MediaReencodeProgress(MediaReencodeProgress.Phase.SavingCopy, 1f))
        return uri
    }

    private suspend fun publishSafSibling(
        parentUri: Uri,
        mimeType: String,
        displayName: String,
        staged: File,
        onProgress: suspend (MediaReencodeProgress) -> Unit,
    ): Uri {
        val created = DocumentsContract.createDocument(
            resolver,
            parentUri,
            mimeType,
            displayName,
        ) ?: error("The document provider could not create a compressed sibling file.")
        try {
            copyFileToUri(
                source = staged,
                destination = created,
                cancellable = true,
                progressPhase = MediaReencodeProgress.Phase.SavingCopy,
                onProgress = onProgress,
            )
            verifySize(created, staged.length())
            return created
        } catch (cancellation: CancellationException) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw cancellation
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw error
        }
    }

    private suspend fun publishMediaStoreCopy(
        item: CleanableItem,
        mimeType: String,
        displayName: String,
        staged: File,
        onProgress: suspend (MediaReencodeProgress) -> Unit,
    ): Uri {
        check(canPublishMediaStoreCopy()) {
            "This Android version needs the existing legacy storage-write grant before a shared-media copy can be created."
        }
        val collection = mediaCollection(item, mimeType)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (item.origin == CleanerOrigin.MediaStore) {
                        item.relativeLocation?.takeIf { it.isNotBlank() } ?: defaultRelativePath(mimeType)
                    } else {
                        defaultRelativePath(mimeType)
                    },
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val directory = Environment.getExternalStoragePublicDirectory(
                    if (mimeType.startsWith("image/")) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES,
                ).resolve("ApexTuner")
                check(directory.exists() || directory.mkdirs()) {
                    "Android could not create the legacy shared-media destination."
                }
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, uniqueLegacyFile(directory, displayName).absolutePath)
            }
        }
        val created = resolver.insert(collection, values)
            ?: error("MediaStore could not create the compressed copy.")
        try {
            copyFileToUri(
                source = staged,
                destination = created,
                cancellable = true,
                progressPhase = MediaReencodeProgress.Phase.SavingCopy,
                onProgress = onProgress,
            )
            verifySize(created, staged.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val published = resolver.update(
                    created,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                check(published > 0) { "MediaStore could not publish the completed compressed copy." }
            }
            return created
        } catch (cancellation: CancellationException) {
            runCatching { resolver.delete(created, null, null) }
            throw cancellation
        } catch (error: Throwable) {
            runCatching { resolver.delete(created, null, null) }
            throw error
        }
    }

    private suspend fun replaceOriginal(
        item: CleanableItem,
        staged: File,
        onProgress: suspend (MediaReencodeProgress) -> Unit,
    ): Uri {
        val sourceUri = Uri.parse(item.uri)
        val backup = File(
            tempRoot,
            "rollback-${UUID.randomUUID()}.${item.displayName.substringAfterLast('.', "bin").lowercase(Locale.ROOT)}",
        )
        var retainRecoverySnapshot = false
        try {
            onProgress(MediaReencodeProgress(MediaReencodeProgress.Phase.SnapshottingOriginal, 0f))
            coroutineContext.ensureActive()
            check(tempRoot.usableSpace >= safeAdd(item.sizeBytes, TEMP_RESERVE_BYTES)) {
                "Temporary storage changed while compressing; there is no longer enough room for a rollback snapshot. The original was not changed."
            }
            copyUriToFileCancellable(
                source = sourceUri,
                destination = backup,
                expectedBytes = item.sizeBytes,
                onProgress = onProgress,
            )
            verifySize(backup, item.sizeBytes)
            verifyContentsEqualCancellable(backup, sourceUri)
            coroutineContext.ensureActive()
            onProgress(MediaReencodeProgress(MediaReencodeProgress.Phase.ReplacingOriginal, 0f))
            coroutineContext.ensureActive()

            return withContext(NonCancellable) {
                verifySourceStillMatchesSnapshot(backup, sourceUri)
                try {
                    copyFileToUriNonCancellable(staged, sourceUri)
                    onProgress(MediaReencodeProgress(MediaReencodeProgress.Phase.Verifying, null))
                    verifyContentsEqual(staged, sourceUri)
                } catch (commitError: Throwable) {
                    val rollbackSucceeded = runCatching {
                        copyFileToUriNonCancellable(backup, sourceUri)
                        verifyContentsEqual(backup, sourceUri)
                    }.isSuccess
                    if (!rollbackSucceeded) {
                        retainRecoverySnapshot = true
                        throw IllegalStateException(
                            "Replacement failed and ApexTuner could not verify restoration of the original. " +
                                "A verified recovery snapshot was retained in ApexTuner's private temporary storage. " +
                                "Do not modify this source again until you verify it in its owning app.",
                            commitError,
                        )
                    }
                    throw IllegalStateException(
                        "Replacement failed safely; the original was restored and verified.",
                        commitError,
                    )
                }
                onProgress(MediaReencodeProgress(MediaReencodeProgress.Phase.Verifying, 1f))
                sourceUri
            }
        } finally {
            if (!retainRecoverySnapshot) {
                runCatching { backup.delete() }
            }
        }
    }

    private suspend fun copyUriToFileCancellable(
        source: Uri,
        destination: File,
        expectedBytes: Long,
        onProgress: suspend (MediaReencodeProgress) -> Unit,
    ) {
        val total = expectedBytes.coerceAtLeast(1L)
        resolver.openInputStream(source)?.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(
                        MediaReencodeProgress(
                            phase = MediaReencodeProgress.Phase.SnapshottingOriginal,
                            fraction = (copied.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f),
                        ),
                    )
                }
                output.flush()
                output.fd.sync()
            }
        } ?: error("Android could not snapshot the original before replacement.")
    }

    private suspend fun copyFileToUri(
        source: File,
        destination: Uri,
        cancellable: Boolean,
        progressPhase: MediaReencodeProgress.Phase,
        onProgress: suspend (MediaReencodeProgress) -> Unit,
    ) {
        val total = source.length().coerceAtLeast(1L)
        resolver.openOutputStream(destination, "wt")?.use { raw ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            FileInputStream(source).buffered(COPY_BUFFER_BYTES).use { input ->
                var copied = 0L
                while (true) {
                    if (cancellable) coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    raw.write(buffer, 0, read)
                    copied += read
                    onProgress(
                        MediaReencodeProgress(
                            phase = progressPhase,
                            fraction = (copied.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f),
                        ),
                    )
                }
                raw.flush()
            }
        } ?: error("Android could not open the output destination.")
    }

    private fun copyFileToUriNonCancellable(source: File, destination: Uri) {
        resolver.openOutputStream(destination, "wt")?.use { raw ->
            FileInputStream(source).buffered(COPY_BUFFER_BYTES).use { input ->
                input.copyTo(raw, COPY_BUFFER_BYTES)
                raw.flush()
            }
        } ?: error("Android could not open the original for replacement.")
    }

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { output ->
            output.fd.sync()
        }
    }

    private suspend fun verifyContentsEqualCancellable(file: File, uri: Uri) {
        val left = digestCancellable(FileInputStream(file))
        val right = resolver.openInputStream(uri)?.let { digestCancellable(it) }
            ?: error("Android could not reopen the original for rollback-snapshot verification.")
        check(left.contentEquals(right)) {
            "The rollback snapshot did not match the original before replacement."
        }
    }

    private fun verifySourceStillMatchesSnapshot(file: File, uri: Uri) {
        val snapshotDigest = digest(FileInputStream(file))
        val sourceDigest = resolver.openInputStream(uri)?.let(::digest)
            ?: error("Android could not reopen the original immediately before replacement.")
        check(snapshotDigest.contentEquals(sourceDigest)) {
            "The original changed after ApexTuner created its rollback snapshot, so replacement was cancelled without modifying it."
        }
    }

    private fun verifySize(uri: Uri, expectedBytes: Long) {
        if (expectedBytes < 0L) return
        val actual = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        if (actual >= 0L) {
            check(actual == expectedBytes) {
                "The output provider reported $actual bytes after writing $expectedBytes bytes."
            }
        }
    }

    private fun verifySize(file: File, expectedBytes: Long) {
        if (expectedBytes <= 0L) return
        check(file.length() == expectedBytes) {
            "The rollback snapshot is incomplete."
        }
    }

    private fun verifyContentsEqual(file: File, uri: Uri) {
        val left = digest(FileInputStream(file))
        val right = resolver.openInputStream(uri)?.use(::digest)
            ?: error("Android could not reopen the replaced source for verification.")
        check(left.contentEquals(right)) {
            "The replaced content did not match the staged output."
        }
    }

    private suspend fun digestCancellable(input: InputStream): ByteArray = input.use { source ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            coroutineContext.ensureActive()
            val read = source.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest()
    }

    private fun digest(input: InputStream): ByteArray = input.use { source ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest()
    }

    private suspend fun probeMissingMetadata(item: CleanableItem): CleanableItem {
        val resolvedMime = item.mimeType ?: runCatching { resolver.getType(Uri.parse(item.uri)) }.getOrNull()
        if (item.width != null && item.height != null &&
            (item.category != CleanerCategory.Video || item.durationMillis != null) &&
            resolvedMime == item.mimeType
        ) {
            return item
        }

        val uri = Uri.parse(item.uri)
        return when (item.category) {
            CleanerCategory.Image -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                coroutineContext.ensureActive()
                item.copy(
                    mimeType = resolvedMime ?: bounds.outMimeType,
                    width = item.width ?: bounds.outWidth.takeIf { it > 0 },
                    height = item.height ?: bounds.outHeight.takeIf { it > 0 },
                )
            }
            CleanerCategory.Video -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    coroutineContext.ensureActive()
                    item.copy(
                        mimeType = resolvedMime,
                        width = item.width ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                        height = item.height ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                        durationMillis = item.durationMillis
                            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                    )
                } finally {
                    retriever.release()
                }
            }
            else -> item.copy(mimeType = resolvedMime)
        }
    }

    private fun canReplaceOriginal(item: CleanableItem): Boolean = when (item.origin) {
        CleanerOrigin.MediaStore -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> true
            else -> hasLegacyWritePermission()
        }
        CleanerOrigin.SafTree, CleanerOrigin.SafDocument -> item.canWrite
        CleanerOrigin.SelectedMedia -> false
    }

    private fun replaceAccessReason(item: CleanableItem): String = when (item.origin) {
        CleanerOrigin.SelectedMedia ->
            "Android's Photo Picker grant is read-only. Save a compressed copy instead."
        CleanerOrigin.MediaStore ->
            "The existing legacy media-write permission is required to replace shared media on Android 8–9."
        CleanerOrigin.SafTree, CleanerOrigin.SafDocument ->
            "This document provider did not grant write access to the original."
    }

    private fun canCreateSafSibling(item: CleanableItem): Boolean =
        item.origin == CleanerOrigin.SafTree &&
            item.parentDocumentUri != null &&
            item.canCreateSibling

    private fun canPublishMediaStoreCopy(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasLegacyWritePermission()

    private fun hasLegacyWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

    private fun mediaCollection(item: CleanableItem, mimeType: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val volume = if (item.origin == CleanerOrigin.MediaStore) {
                runCatching { MediaStore.getVolumeName(Uri.parse(item.uri)) }.getOrNull()
            } else {
                null
            } ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
            if (mimeType.startsWith("image/")) {
                MediaStore.Images.Media.getContentUri(volume)
            } else {
                MediaStore.Video.Media.getContentUri(volume)
            }
        } else {
            if (mimeType.startsWith("image/")) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        }
    }

    private fun defaultRelativePath(mimeType: String): String =
        if (mimeType.startsWith("image/")) {
            "${Environment.DIRECTORY_PICTURES}/ApexTuner"
        } else {
            "${Environment.DIRECTORY_MOVIES}/ApexTuner"
        }

    private fun compressedName(original: String, outputExtension: String): String {
        val base = original.substringBeforeLast('.', original)
            .trim()
            .ifBlank { "media" }
            .replace('/', '_')
            .replace('\\', '_')
            .take(MAX_BASENAME_CHARS)
        val suffix = UUID.randomUUID().toString().take(8)
        return "$base-compressed-$suffix.$outputExtension"
    }

    private fun uniqueLegacyFile(directory: File, displayName: String): File {
        val requested = directory.resolve(displayName)
        if (!requested.exists()) return requested
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        for (index in 2..MAX_NAME_ATTEMPTS) {
            val candidate = directory.resolve(
                if (extension.isBlank()) "$base-$index" else "$base-$index.$extension",
            )
            if (!candidate.exists()) return candidate
        }
        error("ApexTuner could not allocate a unique legacy output name.")
    }

    private fun calculateDecodeSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sample = 1
        while (
            sample <= Int.MAX_VALUE / 2 &&
            sourceWidth / (sample * 2) >= targetWidth &&
            sourceHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        return sample
    }

    @Suppress("DEPRECATION")
    private fun readExifOrientation(uri: Uri): Int {
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    @Suppress("DEPRECATION")
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
    }

    @Suppress("DEPRECATION")
    private fun compressFormat(mimeType: String): Bitmap.CompressFormat = when (mimeType) {
        "image/jpeg" -> Bitmap.CompressFormat.JPEG
        "image/png" -> Bitmap.CompressFormat.PNG
        "image/webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        else -> error("Unsupported image output format: $mimeType")
    }

    private fun safeAdd(a: Long, b: Long): Long {
        val left = a.coerceAtLeast(0L)
        val right = b.coerceAtLeast(0L)
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    private class CancellationCheckingOutputStream(
        output: OutputStream,
        private val checkActive: () -> Unit,
    ) : FilterOutputStream(output) {
        override fun write(b: Int) {
            checkActive()
            out.write(b)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            checkActive()
            out.write(buffer, offset, length)
        }
    }

    private companion object {
        const val TEMP_DIRECTORY_NAME = "media-reencode"
        const val IMAGE_OUTPUT_BUFFER_BYTES = 128 * 1024
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val MAX_BASENAME_CHARS = 120
        const val MAX_NAME_ATTEMPTS = 1_000
        const val TEMP_RESERVE_BYTES = 64L * 1024L * 1024L
    }
}
