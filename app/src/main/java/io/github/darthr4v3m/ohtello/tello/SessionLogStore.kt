package io.github.darthr4v3m.ohtello.tello

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the console of the last few app sessions on disk.
 *
 * The in-app console dies with the process, which is the wrong moment: a
 * connection that misbehaved is usually noticed after the fact, by which point
 * the evidence is gone. Sessions are written as they happen rather than saved
 * on exit, so a log survives even if the app is killed mid-flight.
 *
 * Deliberately free of Android types — it takes a directory — so the rotation
 * can be unit tested.
 */
class SessionLogStore(
    private val directory: File,
    private val maxSessions: Int = MAX_SESSIONS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    private val lineStamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var currentSession: File? = null

    /**
     * Opens a log for this run of the app and drops the oldest ones beyond
     * [maxSessions]. [header] is written first: the build and device, so a log
     * sent on later says what produced it.
     */
    fun startSession(header: String) {
        synchronized(lock) {
            directory.mkdirs()
            prune(keep = maxSessions - 1)

            val file = File(directory, "session-${fileStamp.format(Date(clock()))}.log")
            runCatching {
                file.writeText("$header\n")
                currentSession = file
            }
        }
    }

    /**
     * Appends one console line. Failures are swallowed: logging must not break
     * flying.
     *
     * Formatting happens under the lock as well as the write. SimpleDateFormat
     * carries mutable state, and this is called from several threads at once —
     * the reply waiter under the send mutex, a priority land or emergency that
     * deliberately bypasses it, and disconnect on the main thread. Formatting
     * outside the lock let those corrupt each other, and the exception that
     * comes out of a concurrently used SimpleDateFormat would propagate through
     * log() into the send path and take the app down mid-flight.
     */
    fun append(entry: CommandLogEntry) {
        val file = currentSession ?: return
        synchronized(lock) {
            val line = buildString {
                append(lineStamp.format(Date(entry.timestampMillis)))
                append("  ")
                append(entry.kind.name.padEnd(KIND_WIDTH))
                append("  ")
                append(entry.text)
                append('\n')
            }
            runCatching { file.appendText(line) }
        }
    }

    /** Every stored session, oldest first. */
    fun sessions(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * All stored sessions as one blob for sharing, **newest session first**, so
     * the run you just had is at the top rather than after nine older ones.
     * Lines stay in order within a session — reversing those would make a
     * sequence of commands unreadable, and the sequence is the whole point.
     *
     * Sessions are added whole until [maxChars] runs out, so the newest one is
     * never the one that gets cut: an intent carrying a few megabytes of text
     * is rejected outright, and older history is the part worth losing. The one
     * session that straddles the limit contributes its most recent lines.
     */
    fun shareableText(maxChars: Int = MAX_SHARE_CHARS): String {
        val newestFirst = synchronized(lock) {
            sessions().reversed().map { file ->
                val body = runCatching { file.readText() }.getOrDefault("<unreadable>\n")
                "===== ${file.name} =====\n$body"
            }
        }

        val out = StringBuilder()
        for (block in newestFirst) {
            if (out.length + block.length <= maxChars) {
                out.append(block)
                continue
            }
            // Whatever room is left goes to the end of this session, which is
            // its most recent lines, then stop: everything after is older still.
            val room = maxChars - out.length
            if (room > 0) out.append(block.takeLast(room))
            out.append(TRUNCATION_MARKER)
            break
        }
        return out.toString()
    }

    private fun prune(keep: Int) {
        val sessions = sessions()
        if (sessions.size <= keep) return
        sessions.take(sessions.size - keep).forEach { runCatching { it.delete() } }
    }

    companion object {
        const val MAX_SESSIONS = 10
        const val MAX_SHARE_CHARS = 200_000

        private const val PREFIX = "session-"
        private const val KIND_WIDTH = 9

        /** Says the blob was cut rather than the phone having no older logs. */
        private const val TRUNCATION_MARKER = "\n…\n"
    }
}
