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

    /** Appends one console line. Failures are swallowed: logging must not break flying. */
    fun append(entry: CommandLogEntry) {
        val file = currentSession ?: return
        val line = buildString {
            append(lineStamp.format(Date(entry.timestampMillis)))
            append("  ")
            append(entry.kind.name.padEnd(KIND_WIDTH))
            append("  ")
            append(entry.text)
            append('\n')
        }
        synchronized(lock) {
            runCatching { file.appendText(line) }
        }
    }

    /** Every stored session, oldest first. */
    fun sessions(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * All stored sessions as one blob for sharing, newest last, trimmed from the
     * front to [maxChars] — the recent end is the interesting one, and an intent
     * that carries a few megabytes of text will be rejected.
     */
    fun shareableText(maxChars: Int = MAX_SHARE_CHARS): String {
        val text = synchronized(lock) {
            sessions().joinToString("\n") { file ->
                val body = runCatching { file.readText() }.getOrDefault("<unreadable>\n")
                "===== ${file.name} =====\n$body"
            }
        }
        return if (text.length <= maxChars) text else "…\n" + text.takeLast(maxChars)
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
    }
}
