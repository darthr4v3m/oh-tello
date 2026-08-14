package io.github.darthr4v3m.ohtello.tello.protocol

/**
 * A single reply from the drone's command port.
 *
 * The drone answers control commands with `ok` or an error string, and query
 * commands (`battery?`, `sn?`, ...) with a bare value.
 */
sealed interface TelloResponse {

    /** The reply exactly as it came off the wire, minus padding and whitespace. */
    val raw: String

    /** A control command succeeded. */
    data class Ok(override val raw: String) : TelloResponse

    /** A query command answered with a value, e.g. `87` for `battery?`. */
    data class Value(override val raw: String, val value: String) : TelloResponse

    /** The drone rejected the command, or answered something unusable. */
    data class Failure(override val raw: String, val message: String) : TelloResponse

    /** No reply arrived before the timeout. Nothing was heard from the drone. */
    data object Timeout : TelloResponse {
        override val raw: String = ""
    }

    /**
     * The command never left the phone — no socket, or the write itself failed.
     *
     * Distinct from [Failure] on purpose. "The drone said no" means the link is
     * alive; "I could not reach the drone" means it may well be gone. Powering
     * the drone off takes its access point with it, and the socket is pinned to
     * that network, so a dead link shows up here rather than as a timeout.
     */
    data class Unreachable(val message: String) : TelloResponse {
        override val raw: String = ""
    }

    val isSuccess: Boolean get() = this is Ok || this is Value

    /**
     * Whether the drone was heard from at all. False for both a timeout and an
     * unreachable link; true even for a rejection, since a drone that says
     * `error` is a drone that is still there.
     */
    val heardFromDrone: Boolean get() = this !is Timeout && this !is Unreachable
}

/**
 * Parses a raw datagram payload from the command port.
 *
 * Replies arrive with trailing `\r\n` and, depending on how the buffer was
 * read, NUL padding; both are stripped before matching. Error strings are not
 * a closed set — the drone emits at least `error`, `error Not joystick`,
 * `error Auto land`, `error Motor stop`, `out of range`, `forced stop` and
 * `unknown command: ...` — so anything that is not `ok` and not a plain value
 * is treated as a failure rather than silently passed through as a value.
 */
fun parseTelloResponse(raw: String): TelloResponse {
    val clean = raw.trim { it.isWhitespace() || it.code == 0 }
    if (clean.isEmpty()) return TelloResponse.Failure(raw, "empty response")

    val lower = clean.lowercase()
    return when {
        lower == "ok" -> TelloResponse.Ok(clean)

        lower.startsWith("error") -> {
            val detail = clean.substring("error".length).trim().trim(':').trim()
            TelloResponse.Failure(clean, detail.ifEmpty { "error" })
        }

        FAILURE_PREFIXES.any { lower.startsWith(it) } -> TelloResponse.Failure(clean, clean)

        else -> TelloResponse.Value(clean, clean)
    }
}

/**
 * Whether a datagram off the command port is plausibly an SDK reply at all.
 *
 * Port 8889 does not only carry SDK text. The Tello also speaks a binary
 * protocol on it — packets that start `cc` — and a freshly powered drone has
 * been seen pushing one at the phone before it answers the handshake. Decoded
 * as ASCII that is mojibake, and taken as the reply to `command` it fails the
 * connection outright with a line of question marks.
 *
 * Every SDK reply is printable ASCII, optionally with line endings and NUL
 * padding, so a single byte outside that range rules the packet out. Requiring
 * one printable character also rejects a datagram that is nothing but padding.
 */
fun looksLikeSdkReply(bytes: ByteArray, offset: Int, length: Int): Boolean {
    var printable = 0
    for (index in offset until offset + length) {
        when (bytes[index].toInt() and 0xFF) {
            0, 0x09, 0x0A, 0x0D -> Unit // NUL padding, tab, CR, LF
            in 0x20..0x7E -> printable++
            else -> return false
        }
    }
    return printable > 0
}

/**
 * Renders a datagram as hex for the console. Logging bytes that are not text
 * as if they were prints question marks and loses the evidence; the first few
 * bytes are what identify the packet.
 */
fun hexPreview(bytes: ByteArray, offset: Int, length: Int, maxBytes: Int = HEX_PREVIEW_BYTES): String {
    val shown = minOf(length, maxBytes)
    val hex = (offset until offset + shown).joinToString(" ") {
        (bytes[it].toInt() and 0xFF).toString(16).padStart(2, '0')
    }
    return if (shown < length) "$hex … ($length bytes)" else hex
}

private const val HEX_PREVIEW_BYTES = 16

private val FAILURE_PREFIXES = listOf(
    "unknown command",
    "out of range",
    "forced stop",
)
