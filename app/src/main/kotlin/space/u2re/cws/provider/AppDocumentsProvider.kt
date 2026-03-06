package space.u2re.cws.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import space.u2re.cws.R
import java.io.File
import java.io.FileNotFoundException

class AppDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val INTERNAL_ROOT_ID = "space.u2re.cws.internal"
        private const val EXTERNAL_ROOT_ID = "space.u2re.cws.external"
        
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
        )
    }

    private var internalDir: File? = null
    private var externalDir: File? = null

    override fun onCreate(): Boolean {
        // Fallbacks to literal paths just in case system returns null or unexpected paths
        internalDir = context?.applicationInfo?.dataDir?.let { File(it) } ?: context?.getExternalFilesDir(null) ?: File("/storage/emulated/0/Android/data/space.u2re.cws/")
        externalDir = context?.getExternalFilesDir(null) ?: File("/storage/emulated/0/Android/data/space.u2re.cws/")
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        internalDir?.let { dir ->
            val row = result.newRow()
            row.add(Root.COLUMN_ROOT_ID, INTERNAL_ROOT_ID)
            row.add(Root.COLUMN_SUMMARY, dir.absolutePath)
            row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            row.add(Root.COLUMN_TITLE, "CWS Internal")
            row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(dir))
            row.add(Root.COLUMN_MIME_TYPES, "*/*")
            row.add(Root.COLUMN_AVAILABLE_BYTES, dir.freeSpace)
            row.add(Root.COLUMN_ICON, R.drawable.connect_icon)
        }

        externalDir?.let { dir ->
            val row = result.newRow()
            row.add(Root.COLUMN_ROOT_ID, EXTERNAL_ROOT_ID)
            row.add(Root.COLUMN_SUMMARY, dir.absolutePath)
            row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            row.add(Root.COLUMN_TITLE, "CWS External")
            row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(dir))
            row.add(Root.COLUMN_MIME_TYPES, "*/*")
            row.add(Root.COLUMN_AVAILABLE_BYTES, dir.freeSpace)
            row.add(Root.COLUMN_ICON, R.drawable.connect_icon)
        }

        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        try {
            val file = getFileForDocId(documentId)
            includeFile(result, documentId, file)
        } catch (e: Exception) {
            // Ignore missing files to prevent crashing the file manager
        }
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        try {
            val parent = getFileForDocId(parentDocumentId)
            parent.listFiles()?.forEach { file ->
                includeFile(result, null, file)
            }
        } catch (e: Exception) {
            // Ignore missing directories
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = getFileForDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        
        return try {
            ParcelFileDescriptor.open(file, accessMode)
        } catch (e: FileNotFoundException) {
            throw FileNotFoundException("Failed to open document with id $documentId and mode $mode")
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = getFileForDocId(parentDocumentId)
        val file = if (mimeType == Document.MIME_TYPE_DIR) {
            File(parent, displayName).apply { mkdir() }
        } else {
            File(parent, displayName).apply { createNewFile() }
        }
        return getDocIdForFile(file)
    }

    override fun deleteDocument(documentId: String) {
        val file = getFileForDocId(documentId)
        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    override fun getDocumentType(documentId: String): String {
        val file = getFileForDocId(documentId)
        return getMimeType(file)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            val parent = File(parentDocumentId).canonicalPath
            val child = File(documentId).canonicalPath
            child == parent || child.startsWith("$parent/")
        } catch (e: Exception) {
            false
        }
    }

    private fun getDocIdForFile(file: File): String {
        return file.absolutePath
    }

    private fun getFileForDocId(docId: String): File {
        val file = File(docId)
        val canonicalPath = try {
            file.canonicalPath
        } catch (e: Exception) {
            file.absolutePath
        }

        val intPath = try { internalDir?.canonicalPath } catch (e: Exception) { internalDir?.absolutePath }
        val extPath = try { externalDir?.canonicalPath } catch (e: Exception) { externalDir?.absolutePath }

        if (intPath != null && (canonicalPath == intPath || canonicalPath.startsWith("$intPath/"))) {
            return file
        }
        if (extPath != null && (canonicalPath == extPath || canonicalPath.startsWith("$extPath/"))) {
            return file
        }
        
        throw FileNotFoundException("File not found or access denied: $docId")
    }

    private fun includeFile(result: MatrixCursor, docId: String?, file: File) {
        val flags = if (file.isDirectory) {
            Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
        } else {
            0
        }

        val path = file.absolutePath
        val displayName = when (path) {
            internalDir?.absolutePath -> "CWS Internal"
            externalDir?.absolutePath -> "CWS External"
            else -> file.name
        }

        val row = result.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, docId ?: getDocIdForFile(file))
        row.add(Document.COLUMN_DISPLAY_NAME, displayName)
        row.add(Document.COLUMN_SIZE, file.length())
        row.add(Document.COLUMN_MIME_TYPE, getMimeType(file))
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(Document.COLUMN_FLAGS, flags)
    }

    private fun getMimeType(file: File): String {
        if (file.isDirectory) {
            return Document.MIME_TYPE_DIR
        }
        val extension = file.extension
        if (extension.isNotEmpty()) {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            if (mime != null) return mime
        }
        return "application/octet-stream"
    }
}