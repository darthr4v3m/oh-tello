package io.github.darthr4v3m.ohtello.tello.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelloCommandsTest {

    @Test
    fun `move builds the documented command`() {
        assertEquals("forward 30", TelloCommands.move(MoveDirection.FORWARD, 30))
        assertEquals("back 30", TelloCommands.move(MoveDirection.BACK, 30))
        assertEquals("up 100", TelloCommands.move(MoveDirection.UP, 100))
        assertEquals("down 20", TelloCommands.move(MoveDirection.DOWN, 20))
        assertEquals("left 45", TelloCommands.move(MoveDirection.LEFT, 45))
        assertEquals("right 45", TelloCommands.move(MoveDirection.RIGHT, 45))
    }

    @Test
    fun `move clamps distance to the drone's accepted range`() {
        assertEquals("forward 20", TelloCommands.move(MoveDirection.FORWARD, 5))
        assertEquals("forward 20", TelloCommands.move(MoveDirection.FORWARD, 0))
        assertEquals("forward 20", TelloCommands.move(MoveDirection.FORWARD, -100))
        assertEquals("forward 500", TelloCommands.move(MoveDirection.FORWARD, 900))
    }

    @Test
    fun `turn clamps angle to the drone's accepted range`() {
        assertEquals("cw 90", TelloCommands.turn(TurnDirection.CLOCKWISE, 90))
        assertEquals("ccw 45", TelloCommands.turn(TurnDirection.COUNTER_CLOCKWISE, 45))
        assertEquals("cw 1", TelloCommands.turn(TurnDirection.CLOCKWISE, 0))
        assertEquals("cw 360", TelloCommands.turn(TurnDirection.CLOCKWISE, 1_000))
    }

    @Test
    fun `rc clamps every channel independently`() {
        assertEquals("rc 0 0 0 0", TelloCommands.rc(0, 0, 0, 0))
        assertEquals("rc -100 100 -100 100", TelloCommands.rc(-500, 500, -101, 101))
        assertEquals("rc 10 -20 30 -40", TelloCommands.rc(10, -20, 30, -40))
        assertEquals("rc 0 0 0 0", TelloCommands.RC_NEUTRAL)
    }

    @Test
    fun `speed clamps to 10-100 cm per second`() {
        assertEquals("speed 10", TelloCommands.speed(1))
        assertEquals("speed 100", TelloCommands.speed(150))
        assertEquals("speed 60", TelloCommands.speed(60))
    }

    @Test
    fun `queries are recognised by their trailing question mark`() {
        assertTrue(TelloCommands.isQuery(TelloCommands.QUERY_BATTERY))
        assertTrue(TelloCommands.isQuery("wifi?"))
        assertFalse(TelloCommands.isQuery(TelloCommands.TAKEOFF))
    }

    @Test
    fun `rc is the only command the drone never answers`() {
        assertTrue(TelloCommands.expectsNoResponse("rc 0 0 0 0"))
        assertFalse(TelloCommands.expectsNoResponse(TelloCommands.ENTER_SDK_MODE))
        assertFalse(TelloCommands.expectsNoResponse(TelloCommands.LAND))
        // `rc?` is not a thing, but a command merely starting with the letters
        // r-c must not be mistaken for one.
        assertFalse(TelloCommands.expectsNoResponse("rcfoo 1"))
    }
}
