package io.github.darthr4v3m.ohtello.tello

import io.github.darthr4v3m.ohtello.tello.protocol.TelloState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A flight recorder for the drone's state packets.
 *
 * Deliberately separate from [SessionLogStore] rather than another kind of
 * console line. The console is prose for a human reading a failure; this is a
 * table for a machine doing arithmetic, and the two want opposite things —
 * different formats, different rates, and very different sizes. Mixing them
 * would make the console unreadable and the telemetry unparseable.
 *
 * It exists because the numbers that settle the open questions about this
 * drone are in the state stream and nowhere else. The console log cannot say
 * when the drone stopped flying, since a landed Tello still answers `ok`; the
 * telemetry can, because `time` stops advancing when the motors cut. Same for
 * whether the height error is a scale or an offset: both tests are arithmetic
 * on `h` and `tof` over two altitudes.
 *
 * ## Size
 *
 * The drone pushes ~10 packets a second. Writing all of them would be ~35 KB a
 * minute of mostly identical rows, so samples are decimated to
 * [sampleIntervalMillis] — 2 Hz keeps every manoeuvre visible while costing
 * about 7 KB a minute. Each file is capped at [maxBytesPerSession] and only
 * [maxSessions] are kept, so the worst case on disk is bounded and small.
 *
 * A file is created on the **first packet**, not when the session starts. An
 * app run that never connects therefore leaves nothing behind, so a handful of
 * launches cannot evict the recording of a real flight.
 *
 * Android-free — it takes a directory — so the rotation, the decimation and the
 * cap can all be unit tested.
 */
class TelemetryLogStore(
    private val directory: File,
    private val maxSessions: Int = MAX_SESSIONS,
    private val maxBytesPerSession: Long = MAX_BYTES_PER_SESSION,
    private val sampleIntervalMillis: Long = SAMPLE_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    private val lineStamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    private var header: String? = null
    private var file: File? = null
    private var startedAtMillis: Long = 0
    private var lastSampleAtMillis: Long = 0
    private var bytesWritten: Long = 0
    private var truncated: Boolean = false

    /**
     * Arms the recorder for this run of the app. Nothing is written to disk
     * until the first state packet arrives; [header] is held until then.
     */
    fun startSession(header: String) {
        synchronized(lock) {
            this.header = header
            file = null
            startedAtMillis = 0
            lastSampleAtMillis = 0
            bytesWritten = 0
            truncated = false
        }
    }

    /**
     * Records one state packet, or drops it if the previous sample was too
     * recent. Failures are swallowed for the same reason as in the console log:
     * recording a flight must never be able to interrupt one.
     */
    fun append(state: TelloState) {
        val now = clock()
        synchronized(lock) {
            val header = header ?: return
            if (truncated) return
            if (file != null && now - lastSampleAtMillis < sampleIntervalMillis) return

            val target = file ?: openFile(header, now) ?: return
            lastSampleAtMillis = now

            val row = buildString {
                append(now - startedAtMillis)
                append(',')
                append(lineStamp.format(Date(now)))
                for (column in COLUMNS) {
                    append(',')
                    // Written straight through as the drone sent it. Reparsing
                    // a double only to format it again would cost precision and
                    // invite a locale to put a comma in the middle of a CSV.
                    append(state.fields[column].orEmpty())
                }
                append('\n')
            }

            if (bytesWritten + row.length > maxBytesPerSession) {
                truncated = true
                runCatching { target.appendText("# stopped at $maxBytesPerSession bytes\n") }
                return
            }

            runCatching {
                target.appendText(row)
                bytesWritten += row.length
            }
        }
    }

    /** Every stored recording, oldest first. */
    fun sessions(): List<File> =
        directory.listFiles { candidate -> candidate.isFile && candidate.name.startsWith(PREFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** The file this run is recording into, or null if no packet has arrived yet. */
    fun currentFile(): File? = synchronized(lock) { file }

    private fun openFile(header: String, now: Long): File? {
        directory.mkdirs()
        prune(keep = maxSessions - 1)

        val target = File(directory, "$PREFIX${fileStamp.format(Date(now))}.csv")
        val preamble = "# $header\n# started ${lineStamp.format(Date(now))}\n" +
            "# t_ms: milliseconds since the first packet. clock: matches the console log.\n" +
            "# h,tof: cm. baro: metres. yaw,pitch,roll: degrees. vgz: cm/s. " +
            "agz: 0.001g. time: motor-on seconds.\n" +
            "t_ms,clock," + COLUMNS.joinToString(",") + "\n"

        return runCatching {
            target.writeText(preamble)
            file = target
            startedAtMillis = now
            bytesWritten = 0
            target
        }.getOrNull()
    }

    private fun prune(keep: Int) {
        val sessions = sessions()
        if (sessions.size <= keep) return
        sessions.take(sessions.size - keep).forEach { runCatching { it.delete() } }
    }

    companion object {
        /** Fewer than the console keeps: these files are far larger and age worse. */
        const val MAX_SESSIONS = 5

        /** About two and a half hours of recording. Five of them is ~5 MB. */
        const val MAX_BYTES_PER_SESSION = 1_000_000L

        /** The drone sends ~10 Hz; 2 Hz keeps every manoeuvre and a fifth of the bytes. */
        const val SAMPLE_INTERVAL_MS = 500L

        private const val PREFIX = "telemetry-"

        /**
         * The fields worth recording, in column order. Not every field the
         * drone sends: the rest are constant, redundant, or have never been
         * needed, and each one costs bytes on every row forever.
         */
        private val COLUMNS = listOf(
            "bat", "h", "tof", "baro", "yaw", "pitch", "roll", "vgz", "agz", "time",
        )
    }
}
