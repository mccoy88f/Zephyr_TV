package link.mccoy88f.zephyr

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * The pictures Zephyr TV hands to another app when it shares a title (its poster, with the link: see shareTitle), read
 * from one folder of the cache, read-only. Not exported: an app reads one only through the permission the share grants
 * it, for that picture. (Android's FileProvider is in a library this app does without.)
 */
class SharedImages : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment?.takeIf { NAME.matches(it) } ?: throw FileNotFoundException("no such picture")
        return File(folder(context ?: throw FileNotFoundException("no context")), name).takeIf { it.isFile }
            ?: throw FileNotFoundException("no such picture")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"

    // Its name and size, which chat apps ask before they read it.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private val NAME = Regex("[A-Za-z0-9_-]{1,64}\\.jpg")

        /** Where the shared pictures are kept: the cache, which Android may empty. */
        fun folder(context: Context) = File(context.cacheDir, "shared").apply { mkdirs() }

        /** The address another app reads [file] (in [folder]) at. */
        fun uri(context: Context, file: File): Uri = Uri.parse("content://${context.packageName}.shared/${file.name}")
    }
}
