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
}
