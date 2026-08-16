package io.github.darthr4v3m.ohtello.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * Writes a file into the phone's Downloads folder and returns where it landed.
 *
 * Downloads rather than the app's own storage because the point is to get the
 * evidence somewhere a person can reach it: the Files app, a USB cable, or the
 * share sheet of whatever they use to send it on. App-private storage is
 * unbrowsable on Android 11 and later, which is what made `adb` the only route
 * before this existed.
 *
 * On Android 10 and later this needs no permission at all — the MediaStore
 * grants an app write access to its own Downloads entries. Before that there is
 * no Downloads collection and the public folder needs a storage permission this
 * app deliberately does not ask for, so the file goes to the app's external
 * files directory instead. Less convenient, but it is a real path the caller can
 * print, and it keeps the permission list honest.
 */
fun writeToDownloads(context: Context, name: String, write: (OutputStream) -> Unit): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            // Marked pending so nothing tries to read a half-written zip.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
            ?: error("the Downloads folder would not accept the file")

        resolver.openOutputStream(uri).use { stream ->
            write(stream ?: error("could not open the file for writing"))
        }

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        return "Downloads/$name"
    }

    val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: error("no external storage available")
    val file = File(directory, name)
    file.outputStream().use(write)
    return file.absolutePath
}
