package io.github.darthr4v3m.ohtello.tello

import io.github.darthr4v3m.ohtello.tello.protocol.MoveDirection
import io.github.darthr4v3m.ohtello.tello.protocol.TelloResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    fun `a binary packet does not fail the handshake`() = runBlocking {
        // Seen on a real first connect: the drone answered `command` with a
        // native-protocol packet, which decoded to mojibake and failed the
        // connection. It is not an SDK reply, so it must not be read as one.
        var answered = false
        drone.rawResponder = {
            if (answered) null else byteArrayOf(0xcc.toByte(), 0x58, 0x00, 0x7c, 0x56)
                .also { answered = true }
        }

        assertTrue(controller.connect())
        assertEquals(ConnectionState.Connected, controller.connection.value)

        // The junk is visible in the console rather than silently swallowed —
        // it is the evidence that says which drone state caused it.
        assertTrue(
            controller.log.value.any { it.text.contains("non-SDK packet") && it.text.contains("cc 58") },
        )
    }

    @Test
    fun `a handshake the drone ignores is tried once more before giving up`() = runBlocking {
        // A just-powered drone can drop the first `command` of a session. The
        // pilot's workaround is to press Connect again, so do that for them.
        var attempts = 0
        drone.responder = { if (++attempts == 1) null else "ok" }

        assertTrue(controller.connect())

        assertEquals(listOf("command", "command"), drone.received.toList())
    }

    @Test
    fun `a drone that never answers fails the connection rather than hanging`() = runBlocking {
        drone.responder = { null }

        // Two attempts now, so the worst case is twice the handshake timeout.
        val budget = TelloController.HANDSHAKE_TIMEOUT_MS * 2 +
            TelloController.HANDSHAKE_RETRY_DELAY_MS + 3_000
        val connected = withTimeout(budget) { controller.connect() }

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

        // Unreachable, not Failure: nothing left the phone, so this says nothing
        // about whether the drone would have accepted it.
        assertTrue(response is TelloResponse.Unreachable)
        assertFalse(response.heardFromDrone)
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
    fun `a movement that is never answered gives the controls back quickly`() = runBlocking {
        // What a real flight did: ordered `down 50` near the floor, the drone
        // stopped at its minimum altitude and never replied at all, still
        // hovering. The old flat 20s budget took every movement control away
        // for that whole time — and held the keepalive behind it.
        drone.responder = { command -> if (command.startsWith("down")) null else "ok" }
        assertTrue(controller.connect())

        val elapsed = measureTimeMillis { controller.move(MoveDirection.DOWN, 50) }

        val budget = TelloController.moveTimeoutMillis(50)
        assertTrue("a 50cm move should not budget ${budget}ms", budget < 7_000)
        assertTrue("waited ${elapsed}ms for a 50cm move", elapsed < budget + 1_000)
    }

    @Test
    fun `the movement budget scales with the distance and is capped`() {
        // Measured on a real flight: 30cm took 1.5-2.2s, 100cm took 2.7s. Every
        // budget has to clear that comfortably without approaching the drone's
        // own failsafe, which a hung move would otherwise hold the keepalive off
        // past.
        val short = TelloController.moveTimeoutMillis(20)
        val long = TelloController.moveTimeoutMillis(100)
        assertTrue("a short hop should give up sooner than a long one", short < long)
        assertTrue("20cm budget was ${short}ms", short in 4_000..6_000)
        assertTrue("100cm budget was ${long}ms", long in 7_000..10_000)

        // The longest move the UI offers is 500cm, which is where the cap bites.
        assertEquals(
            TelloController.MOVE_TIMEOUT_CEILING_MS,
            TelloController.moveTimeoutMillis(500),
        )
        // A full turn does not reach the cap. Its budget is deliberately loose:
        // no flight has measured yaw yet, and being generous where there is no
        // data is safer than guessing tight.
        val fullTurn = TelloController.turnTimeoutMillis(360)
        assertTrue("full turn budget was ${fullTurn}ms", fullTurn < TelloController.MOVE_TIMEOUT_CEILING_MS)
        assertTrue("full turn budget was ${fullTurn}ms", fullTurn > 8_000)

        // Nothing may outlast the shortest failsafe the drone is documented to
        // have, since a waiting move holds the keepalive behind it.
        assertTrue(TelloController.MOVE_TIMEOUT_CEILING_MS <= 15_000)
    }

    @Test
    fun `a drone on the ground is not idly hovering`() = runBlocking {
        assertTrue(controller.connect())
        // Telemetry says height 0 and the motor counter is not moving: the drone
        // is on the table, so going quiet is not something to warn about, and
        // claiming it is hovering is a lie.
        repeat(4) {
            drone.pushState("h:0;bat:72;tof:10;time:0;")
            delay(150)
        }

        assertFalse("thought a parked drone was flying", controller.airborne.value)

        delay(IDLE_WARNING_MS + 1_000)

        assertFalse("warned about a grounded drone", controller.pilotIdle.value)
    }

    @Test
    fun `a low hover is airborne even though the height reads zero`() = runBlocking {
        // The case that made this necessary. On a real flight the drone held
        // about 30cm with the motors running for sixteen seconds while `h` read
        // 0 — it reads roughly 35cm low — and both warnings stayed silent.
        assertTrue(controller.connect())

        var motorSeconds = 31
        val telemetry = launch {
            while (isActive) {
                drone.pushState("h:0;bat:45;tof:30;time:${motorSeconds++};")
                delay(200)
            }
        }

        awaitAirborne(true)
        awaitIdle(true)

        telemetry.cancel()
    }

    @Test
    fun `a drone that has flown before is not airborne just because the counter is large`() =
        runBlocking {
            // `time` never resets, so connecting to a drone that flew earlier
            // finds a big number sitting still. Still means still.
            assertTrue(controller.connect())
            repeat(4) {
                drone.pushState("h:0;bat:45;tof:10;time:847;")
                delay(150)
            }

            assertFalse("a frozen counter is not a running motor", controller.airborne.value)
        }

    @Test
    fun `motors that stop are noticed, and the warning goes quiet`() = runBlocking {
        assertTrue(controller.connect())

        var motorSeconds = 10
        repeat(4) {
            drone.pushState("h:0;bat:45;tof:30;time:${motorSeconds++};")
            delay(150)
        }
        awaitAirborne(true)

        // Touchdown: the counter freezes where it stopped.
        repeat(8) {
            drone.pushState("h:0;bat:45;tof:10;time:$motorSeconds;")
            delay(300)
        }

        awaitAirborne(false)
    }

    @Test
    fun `telemetry that says nothing is treated as airborne`() = runBlocking {
        // No packet, or a packet without `h`: warn rather than assume the drone
        // is safely parked. Silence about a flying drone is the costly mistake.
        assertTrue(controller.connect())
        assertTrue("assumed grounded with no telemetry at all", controller.airborne.value)

        drone.pushState("bat:45;tof:30;")
        delay(300)

        assertTrue("assumed grounded with no height field", controller.airborne.value)
    }

    private suspend fun awaitAirborne(expected: Boolean) {
        withTimeout(8_000) {
            while (controller.airborne.value != expected) delay(50)
        }
    }

    @Test
    fun `an airborne drone going quiet does raise the warning`() = runBlocking {
        assertTrue(controller.connect())
        drone.pushState("h:80;bat:72;tof:90;time:12;")
        delay(300)

        awaitIdle(true)
    }

    // --------------------------------------- leaving the app hands the drone over

    @Test
    fun `returning to the app does not resume the keepalive during a failsafe landing`() =
        runBlocking {
            // The bug this exists for: coming back used to speak to the drone
            // within half a second, which it reads as "the pilot is back", so it
            // gave up landing and sat on the floor with its motors running.
            assertTrue(controller.connect())
            val telemetry = launch { hover() }
            awaitAirborne(true)

            controller.setOperatorPresent(false)
            assertTrue("leaving an airborne drone did not hand it over", controller.failsafeLanding.value)

            val spokenWhenLeft = keepAlivesSent()
            controller.setOperatorPresent(true)
            delay(KEEPALIVE_SILENCE_MS)

            assertEquals(
                "coming back to the app spoke to a drone that was landing itself",
                spokenWhenLeft,
                keepAlivesSent(),
            )
            assertTrue("the handover let go on its own", controller.failsafeLanding.value)

            // The one thing that does interrupt it.
            controller.resumeControl()
            withTimeout(KEEPALIVE_SILENCE_MS) {
                while (keepAlivesSent() == spokenWhenLeft) delay(50)
            }
            assertFalse(controller.failsafeLanding.value)

            telemetry.cancel()
        }

    @Test
    fun `pilot commands are refused while the drone is landing itself`() = runBlocking {
        // Locking the keepalive alone would only move the bug: the drone cannot
        // tell one command from another, so a stray tap on the D-pad cancels the
        // landing just as silently.
        assertTrue(controller.connect())
        val telemetry = launch { hover() }
        awaitAirborne(true)
        controller.setOperatorPresent(false)

        val response = controller.move(MoveDirection.FORWARD, 30)

        assertTrue("a move got through", response is TelloResponse.Unreachable)
        assertFalse("the datagram reached the drone", drone.received.any { it.startsWith("forward") })

        telemetry.cancel()
    }

    @Test
    fun `land still reaches a drone that is landing itself`() = runBlocking {
        // Telling it to come down is never the wrong thing to allow while it is
        // coming down, so `land` keeps its own path out.
        assertTrue(controller.connect())
        val telemetry = launch { hover() }
        awaitAirborne(true)
        controller.setOperatorPresent(false)

        controller.land()

        assertTrue("Land was locked out", drone.received.any { it == "land" })

        telemetry.cancel()
    }

    @Test
    fun `backgrounding a parked drone does not lock the controls`() = runBlocking {
        // Ordinary app switching between flights has to stay free of charge.
        assertTrue(controller.connect())
        repeat(4) {
            drone.pushState("h:0;bat:72;tof:10;time:31;")
            delay(150)
        }
        awaitAirborne(false)

        controller.setOperatorPresent(false)
        assertFalse("a drone on the floor was handed over", controller.failsafeLanding.value)

        val spokenWhenLeft = keepAlivesSent()
        controller.setOperatorPresent(true)

        withTimeout(KEEPALIVE_SILENCE_MS) {
            while (keepAlivesSent() == spokenWhenLeft) delay(50)
        }
    }

    @Test
    fun `the lock lifts by itself once the drone is down`() = runBlocking {
        assertTrue(controller.connect())
        var motorSeconds = 40
        val telemetry = launch {
            while (isActive) {
                drone.pushState("h:0;bat:45;tof:30;time:${motorSeconds++};")
                delay(200)
            }
        }
        awaitAirborne(true)
        controller.setOperatorPresent(false)
        assertTrue(controller.failsafeLanding.value)
        telemetry.cancel()

        // Touchdown: the counter freezes where it stopped.
        val frozen = launch {
            while (isActive) {
                drone.pushState("h:0;bat:45;tof:10;time:$motorSeconds;")
                delay(200)
            }
        }
        awaitAirborne(false)

        assertFalse(
            "the pilot still had to ask for controls back after it landed",
            controller.failsafeLanding.value,
        )
        frozen.cancel()
    }

    /** A drone holding a low hover: `h` reads 0, the motor counter advances. */
    private suspend fun hover() {
        var motorSeconds = 20
        while (currentCoroutineContext().isActive) {
            drone.pushState("h:0;bat:60;tof:30;time:${motorSeconds++};")
            delay(200)
        }
    }

    private fun keepAlivesSent(): Int = drone.received.count { it == "command" }

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

        /** Long enough that a keepalive was due, and then some. */
        const val KEEPALIVE_SILENCE_MS = TelloController.KEEPALIVE_INTERVAL_MS + 2_000L
    }
}