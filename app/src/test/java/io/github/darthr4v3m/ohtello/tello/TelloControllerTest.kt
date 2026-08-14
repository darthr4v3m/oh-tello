package io.github.darthr4v3m.ohtello.tello

import io.github.darthr4v3m.ohtello.tello.protocol.MoveDirection
import io.github.darthr4v3m.ohtello.tello.protocol.TelloResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.measureTimeMillis
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives a real [TelloController] against a loopback [FakeDrone]. These use
 * real sockets and real time on purpose: the parts worth testing here are the
 * socket wiring and the timing rules, and a virtual clock would test neither.
 */
class TelloControllerTest {

    private lateinit var drone: FakeDrone
    private lateinit var controller: TelloController

    @Before
    fun setUp() {
        val (dronePort, localPort, statePort) = FakeDrone.freePorts(3)
        drone = FakeDrone(commandPort = dronePort, controllerStatePort = statePort)
        drone.start()
        controller = TelloController(
            endpoint = TelloEndpoint(
                host = "127.0.0.1",
                commandPort = dronePort,
                localCommandPort = localPort,
                statePort = statePort,
            ),
            // Shortened from twelve seconds so the idle tests are not a coffee break.
            idleWarningMillis = IDLE_WARNING_MS,
        )
    }

    @After
    fun tearDown() {
        controller.disconnect()
        drone.close()
    }

    @Test
    fun `connect sends the SDK handshake and reports connected`() = runBlocking {
        assertTrue(controller.connect())

        assertEquals(ConnectionState.Connected, controller.connection.value)
        assertEquals(listOf("command"), drone.received.toList())
    }

    @Test
    fun `a drone that never answers fails the connection rather than hanging`() = runBlocking {
        drone.responder = { null }

        val connected = withTimeout(TelloController.HANDSHAKE_TIMEOUT_MS + 3_000) {
            controller.connect()
        }

        assertFalse(connected)
        assertTrue(controller.connection.value is ConnectionState.Failed)
    }

    @Test
    fun `commands are serialised, never overlapped`() = runBlocking {
        // The drone takes REPLY_DELAY_MS to answer. If the controller let a
        // second command go out before the first reply landed, the drone would
        // see them within a few milliseconds of each other.
        drone.responder = { command ->
            if (command == "command") "ok" else { Thread.sleep(REPLY_DELAY_MS); "ok" }
        }
        assertTrue(controller.connect())

        listOf(
            async { controller.move(MoveDirection.FORWARD, 30) },
            async { controller.move(MoveDirection.BACK, 30) },
        ).awaitAll()

        // Which of the two concurrent callers reaches the mutex first is up to
        // thread scheduling, so the order between them is not a property worth
        // asserting — only that both arrived, and that they did not overlap.
        val commands = drone.received.toList()
        assertEquals(3, commands.size)
        assertEquals("command", commands.first())
        assertEquals(setOf("forward 30", "back 30"), commands.drop(1).toSet())

        // Deliberately not `>= REPLY_DELAY_MS`: sleep can return a millisecond
        // shy of its argument against a clock this coarse, which failed a CI run
        // at 299ms. Overlapping sends would show a gap of nearly zero, so the
        // tolerance costs the test nothing.
        val timings = drone.receivedAtMillis.toList()
        val gap = timings[2] - timings[1]
        assertTrue(
            "second command arrived after only ${gap}ms",
            gap >= REPLY_DELAY_MS - TIMING_SLACK_MS,
        )
    }

    @Test
    fun `a reply that arrives after its command timed out is not read as the next reply`() =
        runBlocking {
            assertTrue(controller.connect())

            // This is the failure mode that puts every later reply one command
            // out of step: `slow?` gives up at 200ms, its answer turns up at
            // 400ms, and `battery?` must still get 86 rather than "late".
            drone.responder = { command ->
                when (command) {
                    "slow?" -> { Thread.sleep(400); "late" }
                    "battery?" -> "86"
                    else -> "ok"
                }
            }

            val timedOut = controller.sendCommand("slow?", timeoutMillis = 200)
            assertEquals(TelloResponse.Timeout, timedOut)

            delay(400) // let the late reply land in the queue

            val battery = controller.sendCommand("battery?", timeoutMillis = 2_000)
            assertEquals(TelloResponse.Value("86", "86"), battery)
        }

    @Test
    fun `state packets are parsed and freshness follows the stream`() = runBlocking {
        assertTrue(controller.connect())
        assertFalse("no telemetry yet", controller.telemetryFresh.value)

        drone.pushState("pitch:0;roll:0;yaw:0;h:12;bat:74;tof:30;time:5;")
        val state = withTimeout(2_000) {
            var seen = controller.state.value
            while (seen == null) {
                delay(20)
                seen = controller.state.value
            }
            seen!!
        }

        assertEquals(74, state.batteryPercent)
        assertEquals(12, state.heightCm)
        assertTrue(controller.telemetryFresh.value)

        // The drone pushes ~10x/sec, so silence for longer than the staleness
        // window is how a dropped link shows up before any command times out.
        delay(TelloController.STALE_TELEMETRY_MS + 750)
        assertFalse(controller.telemetryFresh.value)
    }

    @Test
    fun `errors from the drone come back as failures, not as values`() = runBlocking {
        assertTrue(controller.connect())
        drone.responder = { "error Not joystick" }

        val response = controller.move(MoveDirection.UP, 30)

        assertTrue(response is TelloResponse.Failure)
        assertEquals("Not joystick", (response as TelloResponse.Failure).message)
    }

    @Test
    fun `rc is sent without waiting for a reply the drone will never send`() = runBlocking {
        assertTrue(controller.connect())
        drone.responder = { command -> if (command.startsWith("rc ")) null else "ok" }

        withTimeout(2_000) {
            controller.sendCommand("rc 0 0 0 0")
            controller.sendNoWait("rc 10 0 0 0")
        }

        assertEquals(listOf("command", "rc 0 0 0 0", "rc 10 0 0 0"), drone.received.toList())
    }

    @Test
    fun `commands sent while disconnected fail instead of throwing`() = runBlocking {
        val response = controller.move(MoveDirection.FORWARD, 30)

        assertTrue(response is TelloResponse.Failure)
        assertTrue(drone.received.isEmpty())
    }

    @Test
    fun `disconnect closes the link and a later connect works again`() = runBlocking {
        assertTrue(controller.connect())
        controller.disconnect()
        assertEquals(ConnectionState.Disconnected, controller.connection.value)

        assertTrue(controller.connect())
        assertEquals(listOf("command", "command"), drone.received.toList())
    }

    @Test
    fun `the console log records what was sent and what came back`() = runBlocking {
        assertTrue(controller.connect())
        drone.responder = { "86" }
        controller.sendCommand("battery?")

        val lines = controller.log.value.map { it.text }
        assertTrue(lines.any { it == "→ battery?" })
        assertTrue(lines.any { it == "← 86" })
    }

    @Test
    fun `land jumps the queue instead of waiting behind a slow command`() = runBlocking<Unit> {
        drone.responder = { command ->
            if (command == "command") "ok" else { Thread.sleep(SLOW_REPLY_MS); "ok" }
        }
        assertTrue(controller.connect())

        val move = async { controller.move(MoveDirection.FORWARD, 30) }
        awaitReceived { it.contains("forward 30") } // the move now holds the queue

        // What matters is that land does not wait for the queue. Asserting on
        // the drone's side would prove nothing here: FakeDrone reads one packet
        // at a time, so while it sleeps on the move it has not yet dequeued the
        // land sitting in its socket buffer — as a real drone would have.
        val elapsed = measureTimeMillis { controller.land() }
        assertTrue("land waited ${elapsed}ms for the queue", elapsed < SLOW_REPLY_MS / 2)

        move.await()
        awaitReceived { it.contains("land") }
    }

    @Test
    fun `land is repeated, because one lost datagram looks like success`() = runBlocking {
        assertTrue(controller.connect())

        controller.land()

        awaitReceived {
            it.count { command -> command == "land" } == TelloController.STOP_COMMAND_ATTEMPTS
        }
    }

    @Test
    fun `a link that stops answering tears itself down instead of claiming Connected`() =
        runBlocking {
            assertTrue(controller.connect())
            // The drone goes silent: every keepalive from here times out.
            drone.responder = { null }

            val failed = withTimeout(
                25_000,
            ) {
                var state = controller.connection.value
                while (state !is ConnectionState.Failed) {
                    delay(100)
                    state = controller.connection.value
                }
                state
            }

            assertTrue(failed.message.contains("link lost"))
        }

    /** Waits for the fake drone to have dequeued what we expect. */
    private suspend fun awaitReceived(
        timeoutMillis: Long = 5_000,
        predicate: (List<String>) -> Boolean,
    ) {
        withTimeout(timeoutMillis) {
            while (!predicate(drone.received.toList())) delay(20)
        }
    }

    @Test
    fun `going quiet raises the idle warning, and acting clears it`() = runBlocking {
        assertTrue(controller.connect())
        assertFalse("connecting counts as activity", controller.pilotIdle.value)

        awaitIdle(true)

        controller.move(MoveDirection.UP, 30)
        awaitIdle(false)
    }

    @Test
    fun `the keepalive does not count as pilot activity`() = runBlocking {
        assertTrue(controller.connect())

        // The keepalive talks to the drone every few seconds. If it reset the
        // idle clock the warning could never fire, which is the whole point of
        // measuring pilot commands rather than commands.
        awaitIdle(true)
        val keepalives = controller.log.value.count { it.kind == CommandLogEntry.Kind.KEEPALIVE }

        delay(TelloController.KEEPALIVE_INTERVAL_MS + 1_500)

        assertTrue(
            "no keepalive was sent during the wait, so this proves nothing",
            controller.log.value.count { it.kind == CommandLogEntry.Kind.KEEPALIVE } > keepalives,
        )
        assertTrue("a keepalive cleared the idle warning", controller.pilotIdle.value)
    }

    @Test
    fun `disconnecting drops the idle warning`() = runBlocking {
        assertTrue(controller.connect())
        awaitIdle(true)

        controller.disconnect()

        assertFalse(controller.pilotIdle.value)
    }

    @Test
    fun `a drone on the ground is not idly hovering`() = runBlocking {
        assertTrue(controller.connect())
        // Telemetry says height 0: the drone is on the table, so going quiet is
        // not something to warn about, and claiming it is hovering is a lie.
        drone.pushState("h:0;bat:72;tof:10;time:0;")
        delay(300)

        delay(IDLE_WARNING_MS + 1_000)

        assertFalse("warned about a grounded drone", controller.pilotIdle.value)
    }

    @Test
    fun `an airborne drone going quiet does raise the warning`() = runBlocking {
        assertTrue(controller.connect())
        drone.pushState("h:80;bat:72;tof:90;time:12;")
        delay(300)

        awaitIdle(true)
    }

    private suspend fun awaitIdle(expected: Boolean) {
        withTimeout(IDLE_WARNING_MS + 4_000) {
            while (controller.pilotIdle.value != expected) delay(50)
        }
    }

    private companion object {
        /** How long the fake drone takes to answer a movement command. */
        const val REPLY_DELAY_MS = 300L

        /** Allowance for clock granularity on a loaded CI runner. */
        const val TIMING_SLACK_MS = 50L

        /** The real one is twelve seconds; tests would rather not wait that long. */
        const val IDLE_WARNING_MS = 1_500L

        /** A command the fake drone is deliberately slow to answer. */
        const val SLOW_REPLY_MS = 1_500L
    }
}