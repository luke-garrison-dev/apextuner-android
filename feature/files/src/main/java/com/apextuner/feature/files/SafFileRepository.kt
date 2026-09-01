package com.apextuner.feature.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.apextuner.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface SafFileRepository {
    suspend fun persistedTrees(): List<SafLocation>
    suspend fun persistTree(uri: Uri): Boolean
    suspend fun list(uri: Uri): List<SafNode>
    suspend fun rename(uri: Uri, newName: String): Uri
    suspend fun createFolder(parent: Uri, name: String): Uri
    suspend fun copy(source: Uri, targetDirectory: Uri): Uri
    suspend fun move(source: Uri, targetDirectory: Uri): Uri
    suspend fun zip(source: Uri, targetDirectory: Uri, archiveName: String): Uri
    suspend fun extractZip(archive: Uri, targetDirectory: Uri): Int
}

@Singleton
class AndroidSafFileRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SafFileRepository {
    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun persistedTrees(): List<SafLocation> = withContext(ioDispatcher) {
        resolver.persistedUriPermissions
            .asSequence()
            .filter { DocumentsContract.isTreeUri(it.uri) && it.isReadPermission }
            .mapNotNull { permission ->
                runCatching {
                    val root = rootDocumentUri(permission.uri)
                    SafLocation(root.toString(), queryNode(root)?.displayName ?: "Granted folder")
                }.getOrNull()
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()
    }

    override suspend fun persistTree(uri: Uri): Boolean = withContext(ioDispatcher) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, flags)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    override suspend fun list(uri: Uri): List<SafNode> = withContext(ioDispatcher) {
        val documentId = DocumentsContract.getDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        val result = ArrayList<SafNode>()
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            while (cursor.moveToNext()) {
                coroutineContext.ensureActive()
                val id = cursor.getString(idIndex)
                val child = DocumentsContract.buildDocumentUriUsingTree(uri, id)
                val mime = cursor.getString(mimeIndex)
                val flags = if (flagsIndex >= 0) cursor.getLong(flagsIndex) else 0L
                result += SafNode(
                    uri = child.toString(),
                    documentId = id,
                    displayName = cursor.getString(nameIndex) ?: "Unnamed",
                    mimeType = mime,
                    sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                    lastModifiedEpochMillis = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else null,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    canWrite = flags and (
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE.toLong() or
                            DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong() or
                            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong()
                        ) != 0L,
                )
            }
        }
        result.sortedWith(compareByDescending<SafNode> { it.isDirectory }.thenBy { it.displayName.lowercase() })
    }

    override suspend fun rename(uri: Uri, newName: String): Uri = withContext(ioDispatcher) {
        val safeName = validateSimpleName(newName)
        DocumentsContract.renameDocument(resolver, uri, safeName)
            ?: error("The document provider refused the rename.")
    }

    override suspend fun createFolder(parent: Uri, name: String): Uri = withContext(ioDispatcher) {
        DocumentsContract.createDocument(
            resolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            validateSimpleName(name),
        ) ?: error("The document provider could not create the folder.")
    }

    override suspend fun copy(source: Uri, targetDirectory: Uri): Uri = withContext(ioDispatcher) {
        copyRecursive(source, targetDirectory)
    }

    override suspend fun move(source: Uri, targetDirectory: Uri): Uri = withContext(ioDispatcher) {
        coroutineContext.ensureActive()
        val copied = copyRecursive(source, targetDirectory)
        try {
            coroutineContext.ensureActive()
            val sourceRemoved = try {
                DocumentsContract.deleteDocument(resolver, source)
            } catch (error: Throwable) {
                val rolledBack = deleteDocumentForRollback(copied)
                val message = if (rolledBack) {
                    "The source could not be removed; the destination copy was removed."
                } else {
                    "The source could not be removed, and ApexTuner could not remove the destination copy. Both items may remain; review them before retrying."
                }
                throw IllegalStateException(message, error)
            }
            if (!sourceRemoved) {
                val rolledBack = deleteDocumentForRollback(copied)
                if (rolledBack) {
                    error("The source could not be removed; the destination copy was removed.")
                }
                error("The source could not be removed, and ApexTuner could not remove the destination copy. Both items may remain; review them before retrying.")
            }
            copied
        } catch (cancelled: CancellationException) {
            if (!deleteDocumentForRollback(copied)) {
                cancelled.addSuppressed(
                    IllegalStateException("Move was cancelled after copying, and the document provider could not remove the destination copy."),
                )
            }
            throw cancelled
        }
    }

    override suspend fun zip(source: Uri, targetDirectory: Uri, archiveName: String): Uri = withContext(ioDispatcher) {
        val safeArchive = validateSimpleName(archiveName).let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        val destination = DocumentsContract.createDocument(resolver, targetDirectory, "application/zip", safeArchive)
            ?: error("The provider could not create the ZIP archive.")
        try {
            resolver.openOutputStream(destination, "w")?.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    val root = queryNode(source) ?: error("Source is no longer available.")
                    writeZipNode(zip, source, validateSimpleName(root.displayName))
                }
            } ?: error("The ZIP destination could not be opened.")
            destination
        } catch (cancelled: CancellationException) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw cancelled
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw error
        }
    }

    override suspend fun extractZip(archive: Uri, targetDirectory: Uri): Int = withContext(ioDispatcher) {
        var extracted = 0
        var extractedBytes = 0L
        val createdDocuments = ArrayList<Uri>()
        try {
            resolver.openInputStream(archive)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = zip.nextEntry ?: break
                        val segments = ZipPathGuard.requireSafe(entry.name)
                        require(extracted < MAX_EXTRACTED_ENTRIES) { "ZIP contains too many entries." }
                        var directory = targetDirectory
                        for (segment in segments.dropLast(1)) {
                            directory = findChildDirectory(directory, segment) ?: DocumentsContract.createDocument(
                                resolver,
                                directory,
                                DocumentsContract.Document.MIME_TYPE_DIR,
                                validateSimpleName(segment),
                            )?.also(createdDocuments::add) ?: error("Could not create extracted directory.")
                        }
                        val leaf = segments.last()
                        if (entry.isDirectory) {
                            if (findChildDirectory(directory, leaf) == null) {
                                DocumentsContract.createDocument(
                                    resolver,
                                    directory,
                                    DocumentsContract.Document.MIME_TYPE_DIR,
                                    validateSimpleName(leaf),
                                )?.also(createdDocuments::add) ?: error("Could not create extracted directory.")
                            }
                        } else {
                            val mime = guessMime(leaf)
                            val output = DocumentsContract.createDocument(resolver, directory, mime, validateSimpleName(leaf))
                                ?.also(createdDocuments::add)
                                ?: error("Could not create extracted file.")
                            resolver.openOutputStream(output, "w")?.use { out ->
                                extractedBytes = copyZipEntryCancellable(zip, out, extractedBytes)
                            } ?: error("Could not open extracted file.")
                        }
                        extracted++
                        zip.closeEntry()
                    }
                }
            } ?: error("The ZIP archive could not be opened.")
            extracted
        } catch (cancelled: CancellationException) {
            if (!rollbackCreatedDocuments(createdDocuments)) {
                cancelled.addSuppressed(
                    IllegalStateException("ZIP extraction was cancelled, and the document provider could not remove every item created by this extraction."),
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            val fullyRolledBack = rollbackCreatedDocuments(createdDocuments)
            if (!fullyRolledBack) {
                throw IllegalStateException(
                    "ZIP extraction failed and the document provider could not remove every item created by this extraction. Review the destination before retrying.",
                    error,
                )
            }
            throw error
        }
    }

    private suspend fun copyRecursive(source: Uri, targetDirectory: Uri): Uri {
        coroutineContext.ensureActive()
        ensureSafeTransferTarget(source, targetDirectory)
        val node = queryNode(source) ?: error("Source document is unavailable.")
        if (node.isDirectory) {
            val destination = DocumentsContract.createDocument(
                resolver,
                targetDirectory,
                DocumentsContract.Document.MIME_TYPE_DIR,
                validateSimpleName(node.displayName),
            ) ?: error("Could not create destination directory.")
            try {
                list(source).forEach { child ->
                    coroutineContext.ensureActive()
                    copyRecursive(Uri.parse(child.uri), destination)
                }
                return destination
            } catch (error: Throwable) {
                runCatching { DocumentsContract.deleteDocument(resolver, destination) }
                throw error
            }
        }
        val destination = DocumentsContract.createDocument(
            resolver,
            targetDirectory,
            node.mimeType.ifBlank { "application/octet-stream" },
            validateSimpleName(node.displayName),
        ) ?: error("Could not create destination document.")
        try {
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(destination, "w")?.use { output ->
                    copyCancellable(input, output)
                } ?: error("Could not open destination document.")
            } ?: error("Could not open source document.")
            return destination
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw error
        }
    }


    private fun deleteDocumentForRollback(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: Throwable) {
        false
    }

    private fun rollbackCreatedDocuments(createdDocuments: List<Uri>): Boolean {
        var complete = true
        createdDocuments.asReversed().forEach { uri ->
            if (!deleteDocumentForRollback(uri)) complete = false
        }
        return complete
    }

    private suspend fun writeZipNode(zip: ZipOutputStream, uri: Uri, relativePath: String) {
        coroutineContext.ensureActive()
        val node = queryNode(uri) ?: return
        if (node.isDirectory) {
            val prefix = relativePath.trimEnd('/') + "/"
            zip.putNextEntry(ZipEntry(prefix))
            zip.closeEntry()
            list(uri).forEach { child ->
                writeZipNode(zip, Uri.parse(child.uri), "$prefix${validateSimpleName(child.displayName)}")
            }
        } else {
            zip.putNextEntry(ZipEntry(relativePath))
            resolver.openInputStream(uri)?.use { input -> copyCancellable(input, zip) }
                ?: error("Could not open ${node.displayName}.")
            zip.closeEntry()
        }
    }

    private suspend fun copyZipEntryCancellable(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        alreadyExtractedBytes: Long,
    ): Long {
        var total = alreadyExtractedBytes
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total = Math.addExact(total, read.toLong())
            require(total <= MAX_EXTRACTED_BYTES) { "ZIP expands beyond ApexTuner's safety limit." }
            output.write(buffer, 0, read)
        }
        output.flush()
        return total
    }

    private suspend fun copyCancellable(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) output.write(buffer, 0, read)
        }
        output.flush()
    }

    private fun ensureSafeTransferTarget(source: Uri, targetDirectory: Uri) {
        if (source == targetDirectory) error("A folder cannot be copied or moved into itself.")
        if (source.authority != targetDirectory.authority) return
        val sourceNode = queryNode(source) ?: return
        if (!sourceNode.isDirectory) return
        val isDescendant = try {
            DocumentsContract.isChildDocument(resolver, source, targetDirectory)
        } catch (_: Exception) {
            error("The document provider cannot verify a safe directory transfer target.")
        }
        require(!isDescendant) { "A folder cannot be copied or moved into one of its own descendants." }
    }

    private fun queryNode(uri: Uri): SafNode? {
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) ?: "Unnamed"
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            val flags = if (flagsIndex >= 0) cursor.getLong(flagsIndex) else 0L
            return SafNode(
                uri.toString(),
                id,
                name,
                mime,
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else null,
                mime == DocumentsContract.Document.MIME_TYPE_DIR,
                flags != 0L,
            )
        }
        return null
    }

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun findChildDirectory(parent: Uri, name: String): Uri? =
        runCatching {
            listBlocking(parent).firstOrNull { it.isDirectory && it.displayName == name }?.let { Uri.parse(it.uri) }
        }.getOrNull()

    private fun listBlocking(uri: Uri): List<SafNode> {
        val documentId = DocumentsContract.getDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        val result = ArrayList<SafNode>()
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val mime = cursor.getString(mimeIndex)
                result += SafNode(
                    DocumentsContract.buildDocumentUriUsingTree(uri, id).toString(),
                    id,
                    cursor.getString(nameIndex) ?: "Unnamed",
                    mime,
                    null,
                    null,
                    mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    true,
                )
            }
        }
        return result
    }

    private fun validateSimpleName(value: String): String {
        val name = value.trim()
        require(name.length in 1..255) { "Name must contain 1–255 characters." }
        require(name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }) {
            "Name contains unsupported path characters."
        }
        return name
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        "txt", "log", "md" -> "text/plain"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_EXTRACTED_ENTRIES = 10_000
        const val MAX_EXTRACTED_BYTES = 4L * 1024L * 1024L * 1024L
    }
}
