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

    /**
     * Height above the takeoff point, cm — but read the caveats before trusting it.
     *
     * The drone's internal height is a decimetre integer, so this only ever
     * arrives in steps of 10 and cannot be more precise than that. It is
     * relative to the pressure datum captured at takeoff, which is known to be
     * captured badly, so it under-reads: measured at 40 during a hover where
     * [timeOfFlightCm] read 76 and the pilot measured ~80. It also drifts over
     * a flight and can legitimately go negative.
     *
     * [timeOfFlightCm] is the accurate one at low altitude.
     */
    val heightCm: Int? get() = int("h")

    /**
     * Distance to whatever is directly below, cm, from the downward
     * time-of-flight sensor. Measured to the ground below rather than to the
     * takeoff plane, so flying off a table changes this while [heightCm] holds.
     *
     * SDK 1.3 documents the valid range as 30–1000. Below 30 is a floor rather
     * than a distance — a drone sitting on the floor reports 10 on this unit —
     * and an out-of-range read is reported as a *large* number, around 6553,
     * not a small one. So `tof < 30` means "no usable distance" and a big value
     * means "nothing in range", never "very high up". Do not test for an exact
     * floor value; it is not documented and may differ between airframes.
     */
    val timeOfFlightCm: Int? get() = int("tof")

    /** Motor-on time since power up, seconds. */
    val flightTimeSeconds: Int? get() = int("time")

    /**
     * Absolute pressure altitude, **metres** — not centimetres, whatever the
     * SDK says.
     *
     * DJI's own 1.3 document contradicts itself here: its read-command table
     * gives `baro?` as `(m)` while its state-packet section calls the same
     * sensor `cm`, and SDK 2.0 carried the wrong one forward. Metres is the
     * reading that survives contact with reality — `djitellopy` multiplies this
     * field by 100 to get centimetres, a hover 80cm off the floor moved it by
     * 0.79, and it reads around 115 at an ordinary ground elevation and goes
     * negative on a high-pressure day. None of that works in centimetres.
     *
     * Unlike [heightCm] this is absolute, so a *difference* between two packets
     * is free of the takeoff-datum error that makes height under-read.
     */
    val barometerMetres: Double? get() = double("baro")

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
