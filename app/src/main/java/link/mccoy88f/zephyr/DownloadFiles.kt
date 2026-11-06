package link.mccoy88f.zephyr

import android.app.DownloadManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * The files Zephyr TV downloaded (see Downloads), read through Android's download manager wherever they were saved
 * (the app's folder, Download, Movies or a drive): the player reads them here, and an app they're shared to (see
 * shareDownload) reads one with the permission the share grants for it. Read-only, not exported, and only the
 * downloads Zephyr TV keeps a note of.
 */
class DownloadFiles : ContentProvider() {
    override fun onCreate() = true

    private fun dm() = context?.getSystemService(DownloadManager::class.java) ?: throw FileNotFoundException("no download manager")

    /** content://<package>.downloads/<download id>/<file name>: the download's id, if it's one of ours. */
    private fun id(uri: Uri): Long {
        val ctx = context ?: throw FileNotFoundException("no context")
        val id = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: throw FileNotFoundException("no such download")
        if (Downloads.all(ctx.filesDir).values.none { it.dm == id }) throw FileNotFoundException("no such download")
        return id
    }

    /** content://<package>.downloads/t/<id>/<file name>: a torrent's, if it's one of ours; the engine has its file. */
    private fun torrent(uri: Uri): Downloads.Entry? {
        val seg = uri.pathSegments
        if (seg.size < 2 || seg[0] != "t") return null
        val ctx = context ?: throw FileNotFoundException("no context")
        return Downloads.all(ctx.filesDir)[seg[1]]?.takeIf { it.torrent } ?: throw FileNotFoundException("no such download")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        // The engine's file, opened there and handed on as it is (the app it's shared to can't ask the engine itself).
        torrent(uri)?.let { e ->
            val ctx = context!!
            return ctx.contentResolver.openFileDescriptor(Torrents.fileUri(ctx, e.id, e.file), "r") ?: throw FileNotFoundException("not in the engine")
        }
        return dm().openDownloadedFile(id(uri))
    }

    override fun getType(uri: Uri): String = if (uri.pathSegments.firstOrNull() == "t") mimeOf(uri.lastPathSegment.orEmpty()) else
        runCatching { dm().getMimeTypeForDownloadedFile(id(uri)) }.getOrNull()?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: mimeOf(uri.lastPathSegment.orEmpty())

    // Its name and size, which the apps it's shared to ask before they read it.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        torrent(uri)?.let { e ->
            val size = runCatching { context!!.contentResolver.openFileDescriptor(Torrents.fileUri(context!!, e.id, e.file), "r")?.use { it.statSize } }.getOrNull() ?: 0L
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(e.file, size)) }
        }
        val id = id(uri)
        val size = runCatching {
            dm().query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)) else null
            }
        }.getOrNull() ?: 0L
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(uri.lastPathSegment.orEmpty(), size))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        /** The address the download [dm], saved as [file], is read at. */
        fun uri(context: Context, dm: Long, file: String): Uri = Uri.Builder().scheme("content")
            .authority("${context.packageName}.downloads").appendPath(dm.toString()).appendPath(file).build()

        /** The address download [e] is read at: the download manager's, or a torrent's from the engine (t/<id>/<file>). */
        internal fun uri(context: Context, e: Downloads.Entry): Uri = if (!e.torrent) uri(context, e.dm, e.file) else Uri.Builder().scheme("content")
            .authority("${context.packageName}.downloads").appendPath("t").appendPath(e.id).appendPath(e.file).build()

        /** A video's type by its name, for the apps it's shared to. */
        fun mimeOf(file: String): String = when (file.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "mpg", "mpeg" -> "video/mpeg"
            else -> "video/*"
        }
    }
}
