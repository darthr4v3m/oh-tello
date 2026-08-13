package io.github.darthr4v3m.ohtello.tello.protocol

/**
 * Command strings for the Tello SDK 2.0 UDP protocol.
 *
 * Everything here is plain Kotlin with no Android dependencies so it can be
 * unit tested on the JVM. Commands are ASCII, sent with no trailing newline.
 *
 * Ranges come from the Tello SDK 2.0 documentation. Values are clamped rather
 * than rejected: a slider or a stuck button should never be able to send the
 * drone an out-of-range value, and "error" responses from the drone are much
 * harder to debug in the air than a clamped value on the ground.
 */
object TelloCommands {

    /** The drone's fixed address when the phone is joined to its access point. */
    const val DRONE_IP = "192.168.10.1"

    /** Command/response port. We bind this locally too, and talk to it on the drone. */
    const val COMMAND_PORT = 8889

    /** The drone pushes telemetry to this local port, unprompted, ~10x/sec. */
    const val STATE_PORT = 8890

    /** Valid distance range for the discrete move commands, in centimetres. */
    const val DISTANCE_MIN_CM = 20
    const val DISTANCE_MAX_CM = 500

    /** Valid rotation range for `cw` / `ccw`, in degrees. */
    const val ANGLE_MIN_DEG = 1
    const val ANGLE_MAX_DEG = 360

    /** Valid range for each of the four `rc` channels. */
    const val RC_MIN = -100
    const val RC_MAX = 100

    /** Valid range for `speed`, in cm/s. */
    const val SPEED_MIN_CM_S = 10
    const val SPEED_MAX_CM_S = 100

    /**
     * Enters SDK mode. Must be the first command sent, and the drone answers
     * `ok`. Also doubles as a harmless keepalive: the Tello lands itself if it
     * hears nothing for 15 seconds.
     */
    const val ENTER_SDK_MODE = "command"

    const val TAKEOFF = "takeoff"
    const val LAND = "land"

    /** Cuts the motors immediately. Answers `ok`; the drone will drop. */
    const val EMERGENCY = "emergency"

    const val QUERY_BATTERY = "battery?"
    const val QUERY_SPEED = "speed?"
    const val QUERY_TIME = "time?"
    const val QUERY_SDK = "sdk?"
    const val QUERY_SERIAL = "sn?"
    const val QUERY_WIFI_SNR = "wifi?"

    /** `up|down|left|right|forward|back <20-500>` — distance is clamped. */
    fun move(direction: MoveDirection, distanceCm: Int): String =
        "${direction.keyword} ${distanceCm.coerceIn(DISTANCE_MIN_CM, DISTANCE_MAX_CM)}"

    /** `cw|ccw <1-360>` — angle is clamped. */
    fun turn(direction: TurnDirection, degrees: Int): String =
        "${direction.keyword} ${degrees.coerceIn(ANGLE_MIN_DEG, ANGLE_MAX_DEG)}"

    /**
     * `rc a b c d` — continuous stick input, each channel clamped to -100..100.
     *
     * The drone does **not** answer `rc`, so it must be sent fire-and-forget;
     * waiting for a response on it will always time out.
     */
    fun rc(leftRight: Int, forwardBack: Int, upDown: Int, yaw: Int): String {
        fun ch(v: Int) = v.coerceIn(RC_MIN, RC_MAX)
        return "rc ${ch(leftRight)} ${ch(forwardBack)} ${ch(upDown)} ${ch(yaw)}"
    }

    /** Neutral sticks. Sent as a watchdog whenever stick input stops. */
    val RC_NEUTRAL: String = rc(0, 0, 0, 0)

    /** `speed <10-100>` in cm/s — applies to the discrete move commands. */
    fun speed(cmPerSecond: Int): String =
        "speed ${cmPerSecond.coerceIn(SPEED_MIN_CM_S, SPEED_MAX_CM_S)}"

    /** True for commands the drone answers with a value rather than `ok`. */
    fun isQuery(command: String): Boolean = command.trim().endsWith("?")

    /** True for commands the drone never answers at all. */
    fun expectsNoResponse(command: String): Boolean = command.trim().startsWith("rc ")
}

/** Directions accepted by the discrete distance-based move commands. */
enum class MoveDirection(val keyword: String) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
    FORWARD("forward"),
    BACK("back"),
}

/** Yaw directions accepted by `cw` / `ccw`. */
enum class TurnDirection(val keyword: String) {
    CLOCKWISE("cw"),
    COUNTER_CLOCKWISE("ccw"),
}
