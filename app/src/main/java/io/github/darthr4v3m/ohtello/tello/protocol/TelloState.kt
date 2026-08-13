package io.github.darthr4v3m.ohtello.tello.protocol

/**
 * One telemetry packet from the drone's state port (8890).
 *
 * The drone pushes these unprompted at roughly 10 Hz in the form
 * `pitch:0;roll:0;yaw:0;vgx:0;...;bat:87;...;agz:-999.00;\r\n`.
 *
 * Every accessor is nullable and reads out of [fields] rather than being
 * parsed eagerly into a fixed schema: firmware revisions add and remove keys
 * (`mid`/`x`/`y`/`z` only appear on EDU units with mission pads enabled), and a
 * packet with one unfamiliar key should still yield a battery reading.
 */
data class TelloState(
    /** The packet as received, trimmed. Shown verbatim in the debug console. */
    val raw: String,
    /** Every `key:value` pair in the packet, in arrival order. */
    val fields: Map<String, String>,
) {
    /** Battery charge, percent. */
    val batteryPercent: Int? get() = int("bat")

    /** Height above the takeoff point, cm, from the barometer/IMU. */
    val heightCm: Int? get() = int("h")

    /** Distance to the ground from the downward time-of-flight sensor, cm. */
    val timeOfFlightCm: Int? get() = int("tof")

    /** Motor-on time since power up, seconds. */
    val flightTimeSeconds: Int? get() = int("time")

    /** Barometer reading, cm. */
    val barometerCm: Double? get() = double("baro")

    /** Lowest and highest board temperature, °C. */
    val temperatureLowC: Int? get() = int("templ")
    val temperatureHighC: Int? get() = int("temph")

    /** Attitude, degrees. */
    val pitchDeg: Int? get() = int("pitch")
    val rollDeg: Int? get() = int("roll")
    val yawDeg: Int? get() = int("yaw")

    /** Ground speed along each axis, cm/s. */
    val speedXCmS: Int? get() = int("vgx")
    val speedYCmS: Int? get() = int("vgy")
    val speedZCmS: Int? get() = int("vgz")

    /** Acceleration along each axis, 0.001 g. */
    val accelerationX: Double? get() = double("agx")
    val accelerationY: Double? get() = double("agy")
    val accelerationZ: Double? get() = double("agz")

    fun int(key: String): Int? = fields[key]?.toIntOrNull()

    fun double(key: String): Double? = fields[key]?.toDoubleOrNull()
}

/**
 * Parses one state datagram, or returns null if the payload holds no usable
 * `key:value` pairs (a truncated packet, or something that is not telemetry).
 */
fun parseTelloState(raw: String): TelloState? {
    val clean = raw.trim { it.isWhitespace() || it.code == 0 }
    if (clean.isEmpty()) return null

    val fields = LinkedHashMap<String, String>()
    for (pair in clean.split(';')) {
        val separator = pair.indexOf(':')
        if (separator <= 0) continue
        val key = pair.substring(0, separator).trim()
        val value = pair.substring(separator + 1).trim()
        if (key.isNotEmpty()) fields[key] = value
    }

    return if (fields.isEmpty()) null else TelloState(raw = clean, fields = fields)
}
