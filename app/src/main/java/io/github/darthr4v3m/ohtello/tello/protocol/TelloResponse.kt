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

    val isSuccess: Boolean get() = this is Ok || this is Value
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

private val FAILURE_PREFIXES = listOf(
    "unknown command",
    "out of range",
    "forced stop",
)
