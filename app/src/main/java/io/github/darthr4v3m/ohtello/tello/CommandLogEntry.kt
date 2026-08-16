package io.github.darthr4v3m.ohtello.tello

/**
 * One line in the debug console: a command we sent, a reply the drone gave, or
 * a note from the controller itself.
 */
data class CommandLogEntry(
    /** Monotonic, unique for the process lifetime. Used as the list key. */
    val id: Long,
    val timestampMillis: Long,
    val kind: Kind,
    val text: String,
) {
    enum class Kind {
        /** A command written to the command socket. */
        SENT,

        /** A reply read back from the command socket. */
        RECEIVED,

        /** Controller lifecycle note: sockets opened, network bound, and so on. */
        INFO,

        /** Something went wrong: a timeout, an `error ...` reply, a socket failure. */
        ERROR,

        /**
         * The idle `command` we send every few seconds so the drone does not
         * auto-land on us. Noisy, so the console hides these unless asked.
         */
        KEEPALIVE,
    }
}
