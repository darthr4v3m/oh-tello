package io.github.darthr4v3m.ohtello.tello.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelloResponseTest {

    @Test
    fun `ok is parsed as success`() {
        assertEquals(TelloResponse.Ok("ok"), parseTelloResponse("ok"))
        assertTrue(parseTelloResponse("ok").isSuccess)
    }

    @Test
    fun `line endings and NUL padding are stripped`() {
        // How replies actually arrive: CRLF terminated, and NUL padded when the
        // read buffer is longer than the payload.
        assertEquals(TelloResponse.Ok("ok"), parseTelloResponse("ok\r\n"))
        assertEquals(TelloResponse.Ok("ok"), parseTelloResponse("ok   "))
        assertEquals(TelloResponse.Ok("ok"), parseTelloResponse("  ok \r\n "))
        assertEquals(
            TelloResponse.Ok("ok"),
            parseTelloResponse("ok\r\n" + Char(0).toString().repeat(8)),
        )
    }

    @Test
    fun `uppercase ok is accepted`() {
        // Some firmware revisions shout. Treating OK as a value rather than a
        // success would fail every handshake on those units.
        assertTrue(parseTelloResponse("OK").isSuccess)
        assertTrue(parseTelloResponse("OK\r\n") is TelloResponse.Ok)
    }

    @Test
    fun `query answers come back as values`() {
        assertEquals(TelloResponse.Value("87", "87"), parseTelloResponse("87\r\n"))
        assertEquals(TelloResponse.Value("20", "20"), parseTelloResponse("20"))
        assertEquals(
            TelloResponse.Value("0TQZGA9CD00T63", "0TQZGA9CD00T63"),
            parseTelloResponse("0TQZGA9CD00T63\r\n"),
        )
    }

    @Test
    fun `error replies are failures with the detail kept`() {
        assertEquals("error", (parseTelloResponse("error") as TelloResponse.Failure).message)
        assertEquals(
            "Not joystick",
            (parseTelloResponse("error Not joystick\r\n") as TelloResponse.Failure).message,
        )
        assertEquals(
            "Auto land",
            (parseTelloResponse("error Auto land") as TelloResponse.Failure).message,
        )
        assertEquals(
            "Motor stop",
            (parseTelloResponse("error: Motor stop") as TelloResponse.Failure).message,
        )
    }

    @Test
    fun `rejections that do not say error are still failures`() {
        assertTrue(parseTelloResponse("out of range") is TelloResponse.Failure)
        assertTrue(parseTelloResponse("unknown command: foo") is TelloResponse.Failure)
        assertTrue(parseTelloResponse("forced stop") is TelloResponse.Failure)
        assertFalse(parseTelloResponse("out of range").isSuccess)
    }

    @Test
    fun `an empty payload is a failure rather than an empty value`() {
        assertTrue(parseTelloResponse("") is TelloResponse.Failure)
        assertTrue(parseTelloResponse("\r\n ") is TelloResponse.Failure)
        assertTrue(parseTelloResponse(Char(0).toString()) is TelloResponse.Failure)
    }

    @Test
    fun `timeout carries no payload`() {
        assertEquals("", TelloResponse.Timeout.raw)
        assertFalse(TelloResponse.Timeout.isSuccess)
    }

    @Test
    fun `binary packets on the command port are not read as replies`() {
        // What a real Tello sent on a first connect, logged as `???V &???`:
        // a native-protocol packet, not an SDK reply. Taken as the answer to
        // `command` it failed the whole handshake.
        val native = byteArrayOf(
            0xcc.toByte(), 0x58, 0x00, 0x7c, 0x56, 0x20, 0x26, 0xf9.toByte(), 0x81.toByte(),
        )
        assertFalse(looksLikeSdkReply(native, 0, native.size))

        val ok = "ok\r\n".toByteArray(Charsets.US_ASCII)
        assertTrue(looksLikeSdkReply(ok, 0, ok.size))

        // Read into an oversized buffer, so the tail is NUL padding.
        val padded = ByteArray(32).also { ok.copyInto(it) }
        assertTrue(looksLikeSdkReply(padded, 0, padded.size))

        // Nothing but padding is not a reply either — there is no text in it.
        assertFalse(looksLikeSdkReply(ByteArray(8), 0, 8))

        // A serial number is the longest thing that must survive the filter.
        val serial = "0TQZGA9CD00T63\r\n".toByteArray(Charsets.US_ASCII)
        assertTrue(looksLikeSdkReply(serial, 0, serial.size))
    }

    @Test
    fun `an unreadable packet is logged as hex rather than question marks`() {
        val native = byteArrayOf(0xcc.toByte(), 0x58, 0x00, 0x7c)
        assertEquals("cc 58 00 7c", hexPreview(native, 0, native.size))

        val long = ByteArray(40) { 0xcc.toByte() }
        assertTrue(hexPreview(long, 0, long.size).endsWith("… (40 bytes)"))
    }

    @Test
    fun `a rejection still counts as having heard from the drone`() {
        // This is the distinction the link-loss detector runs on. A drone that
        // says `error` is a drone that is still there; a send that never left
        // the phone is the opposite, however much it looks like a failure.
        assertTrue(parseTelloResponse("ok").heardFromDrone)
        assertTrue(parseTelloResponse("87").heardFromDrone)
        assertTrue(parseTelloResponse("error Motor stop").heardFromDrone)
        assertTrue(parseTelloResponse("out of range").heardFromDrone)

        assertFalse(TelloResponse.Timeout.heardFromDrone)
        assertFalse(TelloResponse.Unreachable("no route to host").heardFromDrone)
        assertFalse(TelloResponse.Unreachable("boom").isSuccess)
        assertEquals("", TelloResponse.Unreachable("boom").raw)
    }
}
