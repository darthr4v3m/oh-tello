package io.github.darthr4v3m.ohtello.tello

import io.github.darthr4v3m.ohtello.tello.protocol.parseTelloState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelemetryLogStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_760_000_000_000L

    private fun store(
        maxSessions: Int = 5,
        maxBytes: Long = 1_000_000L,
        interval: Long = 500L,
    ) = TelemetryLogStore(
        directory = folder.root.resolve("logs"),
        maxSessions = maxSessions,
        maxBytesPerSession = maxBytes,
        sampleIntervalMillis = interval,
        clock = { now },
    )

    private fun packet(h: Int = 40, tof: Int = 76, time: Int = 28) =
        parseTelloState(
            "pitch:0;roll:1;yaw:5;vgx:0;vgy:0;vgz:0;templ:88;temph:90;" +
                "tof:$tof;h:$h;bat:45;baro:115.02;time:$time;agx:-2.00;agy:-21.00;agz:-995.00;",
        )!!

    @Test
    fun `a recording carries a header, a column row and the fields in order`() {
        val store = store()
        store.startSession("Oh-Tello test build")
        store.append(packet())

        val text = store.sessions().single().readText()
        val lines = text.trim().lines()

        assertTrue(lines[0].startsWith("# Oh-Tello test build"))
        assertEquals("t_ms,clock,bat,h,tof,baro,yaw,pitch,roll,vgz,agz,time", lines[4])

        val row = lines[5].split(",")
        assertEquals("0", row[0]) // first packet is the time origin
        assertEquals("45", row[2]) // bat
        assertEquals("40", row[3]) // h
        assertEquals("76", row[4]) // tof
        assertEquals("115.02", row[5]) // baro, written through as sent
        assertEquals("28", row[11]) // time
    }

    @Test
    fun `nothing is written until a packet arrives`() {
        // An app run that never connects must not leave a file behind — five
        // launches would otherwise evict the recording of a real flight.
        val store = store()
        store.startSession("header")

        assertTrue(store.sessions().isEmpty())
        assertNull(store.currentFile())
    }

    @Test
    fun `packets are decimated to the sample interval`() {
        val store = store(interval = 500L)
        store.startSession("header")

        // The drone pushes ~10 Hz. Ten packets across 900ms is two samples:
        // the first, and one once the interval has elapsed.
        repeat(10) {
            store.append(packet(h = it))
            now += 100
        }

        val rows = store.sessions().single().readLines().filterNot { it.startsWith("#") }.drop(1)
        assertEquals(2, rows.size)
        assertEquals("0", rows[0].split(",")[0])
        assertEquals("500", rows[1].split(",")[0])
    }

    @Test
    fun `a recording stops at the size cap instead of filling the phone`() {
        val store = store(maxBytes = 400L, interval = 0L)

        store.startSession("header")
        repeat(200) {
            store.append(packet())
            now += 100
        }

        val file = store.sessions().single()
        val text = file.readText()
        assertTrue("cap overshot: ${text.length}", text.length < 900)
        assertTrue("the cut must be visible", text.contains("# stopped at 400 bytes"))
        // Once capped it stays capped rather than writing the marker repeatedly.
        assertEquals(1, text.lines().count { it.startsWith("# stopped") })
    }

    @Test
    fun `only the last N recordings are kept`() {
        val store = store(maxSessions = 3)
        repeat(6) { index ->
            now += 1_000
            store.startSession("run $index")
            store.append(packet())
        }

        val kept = store.sessions().map { it.readLines().first() }
        assertEquals(listOf("# run 3", "# run 4", "# run 5"), kept)
    }

    @Test
    fun `console logs and recordings share a directory without pruning each other`() {
        // Both stores write to files/logs. Each must only ever delete its own.
        val directory = folder.root.resolve("logs")
        val console = SessionLogStore(directory, maxSessions = 2) { now }
        val telemetry = TelemetryLogStore(directory, maxSessions = 2, clock = { now })

        repeat(4) { index ->
            now += 1_000
            console.startSession("console $index")
            telemetry.startSession("telemetry $index")
            telemetry.append(packet())
        }

        assertEquals(2, console.sessions().size)
        assertEquals(2, telemetry.sessions().size)
        assertTrue(console.sessions().all { it.name.startsWith("session-") })
        assertTrue(telemetry.sessions().all { it.name.startsWith("telemetry-") })
        // And the console's share text must not sweep up the CSV files.
        assertFalse(console.shareableText().contains("t_ms,clock"))
    }

    @Test
    fun `an unwritable directory does not bring down the caller`() {
        val store = TelemetryLogStore(folder.newFile("not-a-directory"), clock = { now })

        store.startSession("header")
        store.append(packet())

        assertTrue(store.sessions().isEmpty())
    }

    @Test
    fun `appending without a session does nothing rather than throwing`() {
        val store = store()

        store.append(packet())

        assertTrue(store.sessions().isEmpty())
    }

    @Test
    fun `a missing field is left empty rather than guessed at`() {
        // Firmware revisions add and remove keys. A packet without `baro`
        // should still record everything else.
        val store = store()
        store.startSession("header")
        store.append(parseTelloState("h:0;tof:10;bat:42;time:36;")!!)

        val row = store.sessions().single().readLines().last().split(",")
        assertEquals("42", row[2]) // bat
        assertEquals("0", row[3]) // h
        assertEquals("", row[5]) // baro, absent
        assertEquals("36", row[11]) // time
    }
}
