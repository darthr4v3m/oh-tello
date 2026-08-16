package io.github.darthr4v3m.ohtello.tello

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionArchiveTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val at = 1_760_000_000_000L

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().decodeToString()
            }
        }
        return out
    }

    @Test
    fun `the name says which build it came from and when`() {
        val name = SessionArchive.fileName("0.1.0-pr1-29a75e5", at)

        assertTrue(name, name.startsWith("oh-tello-0.1.0-pr1-29a75e5-"))
        assertTrue(name, name.endsWith(".zip"))
    }

    @Test
    fun `a version string that would upset a filesystem is made safe`() {
        // versionName is free-form and has carried odd characters before.
        val name = SessionArchive.fileName("0.1.0 (dev)/local", at)

        assertTrue(name, Regex("^oh-tello-[A-Za-z0-9._-]+\\.zip$").matches(name))
        assertTrue(name, name.contains("0.1.0-dev-local"))
    }

    @Test
    fun `both logs go in, under their own names`() {
        val console = folder.newFile("session-20260816-153610-919.log")
            .apply { writeText("Oh-Tello test\n15:43:20  SENT  → command\n") }
        val telemetry = folder.newFile("telemetry-20260816-153610-919.csv")
            .apply { writeText("t_ms,clock,bat,h\n0,15:43:20.000,45,0\n") }

        val bytes = ByteArrayOutputStream().also {
            val stored = SessionArchive.writeTo(it, listOf(console, telemetry))
            assertEquals(listOf(console.name, telemetry.name), stored)
        }.toByteArray()

        val entries = unzip(bytes)
        assertEquals(setOf(console.name, telemetry.name), entries.keys)
        assertTrue(entries.getValue(console.name).contains("→ command"))
        assertTrue(entries.getValue(telemetry.name).startsWith("t_ms,clock"))
    }

    @Test
    fun `a missing file is skipped rather than losing the whole export`() {
        // Half the evidence beats none: a run that never connected has no
        // recording, and that must not stop the console log being exported.
        val console = folder.newFile("session-1.log").apply { writeText("hello\n") }
        val absent = folder.root.resolve("telemetry-never-written.csv")

        val bytes = ByteArrayOutputStream().also {
            val stored = SessionArchive.writeTo(it, listOf(console, absent))
            assertEquals(listOf("session-1.log"), stored)
        }.toByteArray()

        assertEquals(setOf("session-1.log"), unzip(bytes).keys)
    }

    @Test
    fun `an empty list still produces a readable zip`() {
        val bytes = ByteArrayOutputStream().also {
            assertEquals(emptyList<String>(), SessionArchive.writeTo(it, emptyList()))
        }.toByteArray()

        assertTrue(unzip(bytes).isEmpty())
    }

    @Test
    fun `text compresses, so the zip is smaller than what went into it`() {
        // The whole reason for zipping: a telemetry recording is thousands of
        // near-identical rows.
        val row = "500,15:43:20.000,45,40,76,115.02,5,0,1,0,-995.00,28\n"
        val telemetry = folder.newFile("telemetry-big.csv").apply {
            writeText("t_ms,clock,bat,h,tof,baro,yaw,pitch,roll,vgz,agz,time\n" + row.repeat(2_000))
        }

        val bytes = ByteArrayOutputStream().also {
            SessionArchive.writeTo(it, listOf(telemetry))
        }.toByteArray()

        assertTrue(
            "zip ${bytes.size} vs source ${telemetry.length()}",
            bytes.size < telemetry.length() / 4,
        )
    }
}
