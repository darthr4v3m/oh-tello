package io.github.darthr4v3m.ohtello.tello

import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs one run's evidence — the console log and the telemetry recording — into
 * a single zip.
 *
 * The two files answer different halves of any question about a flight: the
 * console says what was asked of the drone, the recording says what the drone
 * was doing. Sending one without the other means a follow-up, so they travel
 * together. Compressed because they are text, and text zips to almost nothing.
 *
 * Android-free, so the naming and the packing can be unit tested.
 */
object SessionArchive {

    /**
     * A name that says what the file is without being opened: the build it came
     * from and when it was exported. Anything a filesystem might object to is
     * replaced, since the version string is free-form.
     */
    fun fileName(version: String, atMillis: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(atMillis))
        val safe = version.replace(UNSAFE_IN_A_FILENAME, "-").trim('-')
        return "oh-tello-$safe-$stamp.zip"
    }

    /**
     * Writes [files] into a zip on [out], returning the names it managed to
     * store. A file that has vanished or cannot be read is skipped rather than
     * failing the export: half the evidence beats none of it, and the returned
     * list is what the caller should report rather than what it asked for.
     */
    fun writeTo(out: OutputStream, files: List<File>): List<String> {
        val stored = mutableListOf<String>()
        ZipOutputStream(out).use { zip ->
            for (file in files) {
                if (!file.isFile) continue
                runCatching {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    stored += file.name
                }
            }
        }
        return stored
    }

    private val UNSAFE_IN_A_FILENAME = Regex("[^A-Za-z0-9._-]+")
}
