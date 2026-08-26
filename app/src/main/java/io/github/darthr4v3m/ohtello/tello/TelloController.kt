package io.github.darthr4v3m.ohtello.tello

import io.github.darthr4v3m.ohtello.tello.protocol.MoveDirection
import io.github.darthr4v3m.ohtello.tello.protocol.TelloCommands
import io.github.darthr4v3m.ohtello.tello.protocol.TelloResponse
import io.github.darthr4v3m.ohtello.tello.protocol.TelloState
import io.github.darthr4v3m.ohtello.tello.protocol.TurnDirection
import io.github.darthr4v3m.ohtello.tello.protocol.hexPreview
import io.github.darthr4v3m.ohtello.tello.protocol.looksLikeSdkReply
import io.github.darthr4v3m.ohtello.tello.protocol.parseTelloResponse
import io.github.darthr4v3m.ohtello.tello.protocol.parseTelloState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong

/** Where to find the drone. Overridable so tests can run a fake one on loopback. */
data class TelloEndpoint(
    val host: String = TelloCommands.DRONE_IP,
    val commandPort: Int = TelloCommands.COMMAND_PORT,
    val localCommandPort: Int = TelloCommands.COMMAND_PORT,
    val statePort: Int = TelloCommands.STATE_PORT,
)

/**
 * The whole link to the drone: two UDP sockets, a strictly serial command
 * queue, and the telemetry stream, exposed to the UI as [StateFlow]s.
 *
 * The serialisation is the important part. A Tello does not queue commands: it
 * answers exactly one at a time, and anything sent before the previous reply
 * arrives is dropped on the floor — silently, which in flight looks like the
 * drone ignoring you. So every send takes [commandMutex], writes, and waits for
 * the reply or a timeout before the next one may start.
 *
 * The other half of that contract is what happens *after* a timeout: the reply
 * may still turn up a moment later, and if we left it in the queue it would be
 * read as the answer to the following command, putting every subsequent reply
 * one command out of step. Each send therefore drains anything left over first.
 */
class TelloController(
    private val endpoint: TelloEndpoint = TelloEndpoint(),
    private val socketBinder: SocketBinder = SocketBinder.Unbound,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Mirrors the console to disk so a bad connection can be read back later. */
    private val sessionLog: SessionLogStore? = null,
    /** Records the state stream to disk so a flight can be measured afterwards. */
    private val telemetryLog: TelemetryLogStore? = null,
    /** How long without a pilot command before [pilotIdle] is raised. */
    private val idleWarningMillis: Long = IDLE_WARNING_MS,
) {

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _state = MutableStateFlow<TelloState?>(null)
    val state: StateFlow<TelloState?> = _state.asStateFlow()

    /**
     * False when no telemetry packet has arrived for [STALE_TELEMETRY_MS]. The
     * drone pushes state ~10x/sec, so silence is a good proxy for "the link is
     * gone" long before a command times out.
     */
    private val _telemetryFresh = MutableStateFlow(false)
    val telemetryFresh: StateFlow<Boolean> = _telemetryFresh.asStateFlow()

    private val _log = MutableStateFlow<List<CommandLogEntry>>(emptyList())
    val log: StateFlow<List<CommandLogEntry>> = _log.asStateFlow()

    /**
     * True once the pilot has sent nothing for [idleWarningMillis], chosen to
     * sit just inside the drone's own auto-land timer. The SDK documents that
     * as 15s from the last command; a real drone was measured answering after
     * 23s of silence, so treat the figure as a floor rather than a promise.
     *
     * Nothing is wrong when this is set — the keepalive is holding the drone up
     * and it will hover indefinitely. It is a reminder that the only thing
     * keeping it there is this app being in front of you.
     */
    private val _pilotIdle = MutableStateFlow(false)
    val pilotIdle: StateFlow<Boolean> = _pilotIdle.asStateFlow()

    /**
     * Whether the drone is judged to be in the air. One definition, shared by
     * everything that needs to know — the idle banner here and the backgrounding
     * notification in the UI both used to carry their own copy, and a copy is
     * one more place for the two to disagree. See [judgeAirborne].
     */
    private val _airborne = MutableStateFlow(false)
    val airborne: StateFlow<Boolean> = _airborne.asStateFlow()

    /**
     * True once the drone has been handed to its own failsafe and not yet taken
     * back. See [setOperatorPresent] and [resumeControl].
     *
     * A latch rather than a mirror of "is the app backgrounded": returning to
     * the app must not, on its own, cancel a landing that is already underway.
     * Kept as the flow itself rather than a `@Volatile` field with a flow beside
     * it — one piece of state, safe to read from the telemetry loop and the UI
     * thread alike, with no second copy to fall out of step.
     */
    private val _failsafeLanding = MutableStateFlow(false)
    val failsafeLanding: StateFlow<Boolean> = _failsafeLanding.asStateFlow()

    private val commandMutex = Mutex()
    private val logIds = AtomicLong(0)

    @Volatile private var commandSocket: DatagramSocket? = null
    @Volatile private var stateSocket: DatagramSocket? = null
    @Volatile private var controllerScope: CoroutineScope? = null
    @Volatile private var replies: Channel<String> = Channel(Channel.UNLIMITED)
    @Volatile private var lastCommandAtMillis: Long = 0
    @Volatile private var lastPilotCommandAtMillis: Long = 0
    @Volatile private var operatorPresent: Boolean = true
    @Volatile private var lastStateAtMillis: Long = 0
    @Volatile private var lastMotorSeconds: Int? = null
    @Volatile private var lastMotorAdvanceAtMillis: Long = 0

    // ---------------------------------------------------------------- lifecycle

    /**
     * Opens both sockets and performs the SDK handshake. Returns true once the
     * drone has answered `ok` to `command`.
     */
    suspend fun connect(): Boolean = withContext(ioDispatcher) {
        disconnect()
        _connection.value = ConnectionState.Connecting
        log(CommandLogEntry.Kind.INFO, "connecting to ${endpoint.host}:${endpoint.commandPort}")

        val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        controllerScope = scope
        replies = Channel(Channel.UNLIMITED)

        try {
            // Each socket is published to its field as soon as it exists, so a
            // failure part way through still leaves teardown() something to close.
            val command = openSocket(endpoint.localCommandPort)
            commandSocket = command
            // bindToWifi has to happen while the socket is still unconnected,
            // which openSocket takes care of; pin the drone address afterwards.
            command.connect(InetAddress.getByName(endpoint.host), endpoint.commandPort)

            val state = openSocket(endpoint.statePort)
            stateSocket = state

            scope.launch { receiveReplies(command) }
            scope.launch { receiveStatePackets(state) }
            scope.launch { trackTelemetryFreshness() }
        } catch (e: Exception) {
            return@withContext failConnection(describeOpenFailure(e))
        }

        // A drone that has just been powered on does not reliably take the first
        // `command` of a session — the first datagram can go missing, or come
        // back as something that is not an SDK reply at all. Pressing Connect a
        // second time is the manual version of this, so do it here instead of
        // asking the pilot to. One extra try, not a retry loop: past two the
        // problem is not flakiness, it is the wrong Wi-Fi network.
        var response = sendCommand(TelloCommands.ENTER_SDK_MODE, HANDSHAKE_TIMEOUT_MS)
        if (response !is TelloResponse.Ok) {
            log(CommandLogEntry.Kind.INFO, "handshake did not take, trying once more")
            delay(HANDSHAKE_RETRY_DELAY_MS)
            response = sendCommand(TelloCommands.ENTER_SDK_MODE, HANDSHAKE_TIMEOUT_MS)
        }

        when (response) {
            is TelloResponse.Ok -> {
                _connection.value = ConnectionState.Connected
                lastPilotCommandAtMillis = clock()
                log(CommandLogEntry.Kind.INFO, "SDK mode entered")
                scope.launch { sendKeepAlives() }
                true
            }

            is TelloResponse.Timeout, is TelloResponse.Unreachable -> failConnection(
                "no reply to `command` — check the phone is joined to the drone's " +
                    "TELLO-XXXXXX network and the drone is powered on",
            )

            else -> failConnection("handshake refused by the drone: ${response.raw}")
        }
    }

    /** Closes both sockets and stops every background loop. Safe to call twice. */
    fun disconnect() {
        if (_connection.value is ConnectionState.Disconnected && commandSocket == null) return
        teardown()
        _connection.value = ConnectionState.Disconnected
        log(CommandLogEntry.Kind.INFO, "disconnected")
    }

    // ----------------------------------------------------------------- commands

    /** `takeoff`. Slow to answer — the drone replies once it is airborne. */
    suspend fun takeoff(): TelloResponse = sendCommand(TelloCommands.TAKEOFF, TAKEOFF_TIMEOUT_MS)

    /**
     * `land`. Jumps the queue and is sent more than once.
     *
     * Never waits its turn: the serial queue can be held by a keepalive burning
     * a five second timeout, or by a move with twenty seconds left on its clock,
     * and a pilot pressing Land does not care which. Repeated because this is one
     * UDP datagram over a marginal 2.4GHz link, where a dropped packet looks
     * exactly like a successful send — and a second `land` costs nothing, the
     * drone being already on its way down.
     */
    suspend fun land() = sendPriority(TelloCommands.LAND, attempts = STOP_COMMAND_ATTEMPTS)

    /**
     * `emergency` — cuts the motors immediately. The drone will drop. Jumps the
     * queue and repeats, for the same reasons as [land], more so.
     */
    suspend fun emergency() =
        sendPriority(TelloCommands.EMERGENCY, attempts = STOP_COMMAND_ATTEMPTS)

    /** Discrete `up|down|left|right|forward|back <cm>`; distance is clamped. */
    suspend fun move(direction: MoveDirection, distanceCm: Int): TelloResponse =
        sendCommand(TelloCommands.move(direction, distanceCm), moveTimeoutMillis(distanceCm))

    /** Discrete `cw|ccw <degrees>`; angle is clamped. */
    suspend fun turn(direction: TurnDirection, degrees: Int): TelloResponse =
        sendCommand(TelloCommands.turn(direction, degrees), turnTimeoutMillis(degrees))

    /**
     * Sends one command and waits for its reply, serialised against every other
     * send. Use [sendNoWait] for `rc`, which the drone never answers.
     */
    suspend fun sendCommand(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): TelloResponse = send(command, timeoutMillis, CommandLogEntry.Kind.SENT)

    /**
     * Writes a command without waiting for a reply, for `rc a b c d`, which the
     * drone answers with nothing at all. Still serialised, so it can never
     * interleave with a command that *is* waiting for its reply.
     */
    suspend fun sendNoWait(command: String) {
        // `rc` reaches here through [send], which has already refused it if the
        // drone is landing itself; this covers any future caller coming direct.
        refusedWhileLandingItself(command)?.let { return }
        val socket = commandSocket ?: return
        withContext(ioDispatcher) {
            commandMutex.withLock {
                try {
                    writeDatagram(socket, command)
                    lastCommandAtMillis = clock()
                } catch (e: IOException) {
                    log(CommandLogEntry.Kind.ERROR, "could not send `$command`: ${e.message}")
                }
            }
        }
    }

    /**
     * Writes a command straight to the socket, jumping the serial queue and not
     * waiting for a reply.
     *
     * This exists for `land` and `emergency` only. Everything else can afford to
     * wait its turn, but a stop command must not sit behind a `forward 500` that
     * has twenty seconds left on its timeout — by the time the queue drained,
     * the reason you pressed it would be over. The cost is one mislabelled line
     * in the console: the reply to this command is whatever the in-flight
     * command's waiter picks up, and the leftover is discarded on the next send.
     */
    suspend fun sendPriority(command: String, attempts: Int = 1) {
        val socket = commandSocket ?: run {
            notConnected(command)
            return
        }
        withContext(ioDispatcher) {
            repeat(attempts) { attempt ->
                if (attempt > 0) delay(STOP_COMMAND_SPACING_MS)
                try {
                    writeDatagram(socket, command)
                    lastCommandAtMillis = clock()
                    lastPilotCommandAtMillis = clock()
                    val note = if (attempts > 1) " (jumped the queue, ${attempt + 1}/$attempts)" else " (jumped the queue)"
                    log(CommandLogEntry.Kind.SENT, "→ $command$note")
                } catch (e: IOException) {
                    log(CommandLogEntry.Kind.ERROR, "could not send `$command`: ${e.message}")
                }
            }
        }
    }

    private suspend fun send(
        command: String,
        timeoutMillis: Long,
        kind: CommandLogEntry.Kind,
    ): TelloResponse {
        val socket = commandSocket ?: return notConnected(command)

        // Keepalives are exempt: they are already held off by [sendKeepAlives]
        // while the latch is set, and by the time one reaches here the pilot has
        // taken control back.
        if (kind != CommandLogEntry.Kind.KEEPALIVE) {
            refusedWhileLandingItself(command)?.let { return it }
        }

        if (TelloCommands.expectsNoResponse(command)) {
            // Guard rail: waiting on an `rc` reply would always burn the full
            // timeout, and a stuck stick is not a good place to stall.
            sendNoWait(command)
            log(kind, "→ $command (no reply expected)")
            return TelloResponse.Ok("")
        }

        return withContext(ioDispatcher) {
            commandMutex.withLock {
                drainLateReplies()
                log(kind, "→ $command")
                lastCommandAtMillis = clock()
                if (kind != CommandLogEntry.Kind.KEEPALIVE) lastPilotCommandAtMillis = clock()

                try {
                    writeDatagram(socket, command)
                } catch (e: IOException) {
                    val message = "could not send `$command`: ${e.message ?: e.toString()}"
                    log(CommandLogEntry.Kind.ERROR, message)
                    return@withLock TelloResponse.Unreachable(message)
                }

                val raw = try {
                    withTimeoutOrNull(timeoutMillis) { replies.receive() }
                } catch (e: ClosedReceiveChannelException) {
                    null
                }

                if (raw == null) {
                    log(
                        CommandLogEntry.Kind.ERROR,
                        "no reply to `$command` within ${timeoutMillis / 1000}s",
                    )
                    return@withLock TelloResponse.Timeout
                }

                val response = parseTelloResponse(raw)
                val replyKind = when {
                    response is TelloResponse.Failure -> CommandLogEntry.Kind.ERROR
                    kind == CommandLogEntry.Kind.KEEPALIVE -> CommandLogEntry.Kind.KEEPALIVE
                    else -> CommandLogEntry.Kind.RECEIVED
                }
                log(replyKind, "← ${response.raw}")
                response
            }
        }
    }

    private fun writeDatagram(socket: DatagramSocket, command: String) {
        val payload = command.toByteArray(Charsets.US_ASCII)
        // The socket is connected to the drone, so the packet needs no address.
        socket.send(DatagramPacket(payload, payload.size))
    }

    /**
     * Throws away replies that arrived after their command had already timed
     * out. Without this, one slow reply shifts every later reply by one command.
     */
    private fun drainLateReplies() {
        while (true) {
            val late = replies.tryReceive().getOrNull() ?: return
            log(CommandLogEntry.Kind.INFO, "discarded late reply: ${late.trim()}")
        }
    }

    // ------------------------------------------------------------ socket loops

    private suspend fun receiveReplies(socket: DatagramSocket) {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (currentCoroutineContext().isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                // Expected on disconnect: closing the socket is how we stop this loop.
                return
            }
            // Not everything arriving here is an SDK reply: the drone also emits
            // binary packets on this port. Letting one into the queue means the
            // next command waiting for a reply reads it as its own.
            if (!looksLikeSdkReply(packet.data, packet.offset, packet.length)) {
                log(
                    CommandLogEntry.Kind.INFO,
                    "ignored a non-SDK packet on the command port: " +
                        hexPreview(packet.data, packet.offset, packet.length),
                )
                continue
            }
            val text = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
            replies.trySend(text)
        }
    }

    private suspend fun receiveStatePackets(socket: DatagramSocket) {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (currentCoroutineContext().isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                return
            }
            val text = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
            val parsed = parseTelloState(text) ?: continue
            lastStateAtMillis = clock()
            _telemetryFresh.value = true
            _state.value = parsed
            noteMotorTime(parsed.flightTimeSeconds)
            // Decimated and capped inside the store, so this stays cheap at the
            // ~10 Hz the drone pushes. Its own lock, not the console's: this
            // must never contend with a command waiting to go out.
            telemetryLog?.append(parsed)
        }
    }

    /**
     * Watches the drone's motor-on counter for movement.
     *
     * The counter never resets and only ever advances while the props turn, so
     * the *value* says nothing useful but a *change* means the motors are
     * running right now. The first reading of a session only sets the baseline:
     * connecting to a drone that has flown before finds a large number sitting
     * still, and that is a landed drone, not a flying one.
     *
     * A decrease would mean the drone rebooted under us; count it as movement,
     * because guessing "landed" there is the dangerous way to be wrong.
     */
    private fun noteMotorTime(seconds: Int?) {
        if (seconds == null) return
        val previous = lastMotorSeconds
        if (previous != null && seconds != previous) lastMotorAdvanceAtMillis = clock()
        lastMotorSeconds = seconds
    }

    /**
     * Whether the drone should be treated as in the air.
     *
     * Three signals, OR'd, and the asymmetry is the whole design: a false
     * "airborne" costs a warning nobody needed, a false "on the ground" costs
     * silence about an aircraft that is about to put itself down. Every term
     * here errs the harmless way.
     *
     * - **Motors turning.** `time` is DJI's own motor-on counter, so a value
     *   that advanced recently means the props are spinning. It is the only
     *   signal that does not go through an altitude estimate, and it is the one
     *   that catches the case that broke us.
     * - **Height above zero.** A second, independent opinion for anything the
     *   barometer can see. Kept as a backstop in case `time` behaves differently
     *   on other firmware, since only one flight has been examined.
     * - **Nothing known.** No telemetry yet, or a packet without `h`: warn.
     *
     * `h` alone used to decide this, and it was wrong. It reads about 35cm low —
     * a fixed offset, settled by 108 samples across a real descent — so it
     * reports 0 for any hover below that. One recording has the drone holding
     * 30cm with the motors running, for sixteen seconds, while `h` said 0 and
     * neither warning fired.
     */
    private fun judgeAirborne(): Boolean {
        val sinceMotorsMoved = clock() - lastMotorAdvanceAtMillis
        val motorsRunning = lastMotorAdvanceAtMillis != 0L &&
            sinceMotorsMoved < MOTORS_RUNNING_WINDOW_MS
        if (motorsRunning) return true

        // Null covers both "no packet yet" and "this firmware omits `h`".
        val height = _state.value?.heightCm ?: return true
        return height > 0
    }

    private suspend fun trackTelemetryFreshness() {
        while (currentCoroutineContext().isActive) {
            val last = lastStateAtMillis
            _telemetryFresh.value = last != 0L && clock() - last < STALE_TELEMETRY_MS

            // Deliberately measured from the last *pilot* command, not the last
            // command: the keepalive is talking to the drone every five seconds
            // and would otherwise reset this forever, which is the opposite of
            // what it is for.
            val idleFor = clock() - lastPilotCommandAtMillis

            _airborne.value = judgeAirborne()

            // Down and still: the failsafe has finished, so give the controls
            // back without making the pilot ask. [judgeAirborne] answers "yes"
            // when there is no telemetry to judge from, so silence holds the
            // lock on rather than releasing it — the harmless direction.
            if (!_airborne.value) _failsafeLanding.value = false

            _pilotIdle.value = _connection.value is ConnectionState.Connected &&
                lastPilotCommandAtMillis != 0L &&
                idleFor >= idleWarningMillis &&
                _airborne.value

            delay(FRESHNESS_TICK_MS)
        }
    }

    /**
     * The drone auto-lands if it hears nothing for 15 seconds, so nudge it with
     * a plain `command` whenever the stick has been idle for a few seconds. It
     * goes through the same mutex as everything else, so it can never cut in
     * front of a command that is waiting for its reply.
     */
    private suspend fun sendKeepAlives() {
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            delay(FRESHNESS_TICK_MS)
            if (_connection.value !is ConnectionState.Connected) continue

            // With nobody watching the screen, the right thing is to stop
            // talking and let the drone's own failsafe put it down. See
            // [operatorPresent]. The latch outlives the backgrounding: a
            // handover already in progress is not undone by the app reappearing.
            if (!operatorPresent || _failsafeLanding.value) continue

            if (clock() - lastCommandAtMillis < KEEPALIVE_INTERVAL_MS) continue

            val response =
                send(TelloCommands.ENTER_SDK_MODE, HANDSHAKE_TIMEOUT_MS, CommandLogEntry.Kind.KEEPALIVE)

            // Nothing else ever moves the link out of Connected: a command that
            // fails logs an error and leaves the UI claiming all is well, and a
            // socket pinned to a Wi-Fi network that has gone away stays dead
            // even after the phone rejoins. Two bad keepalives is ~10s, and
            // tearing down here means the next Connect binds to the current
            // network rather than the corpse.
            //
            // A strike is "the drone was not heard from", not "the reply timed
            // out". Powering the drone off takes its access point with it, and
            // the socket is pinned to that network, so the send throws before
            // anything leaves the phone — an outcome that is worse than silence,
            // not better. Only a real answer, `ok` or `error` alike, clears the
            // count: a drone that rejects a command is still a drone that is there.
            if (!response.heardFromDrone) {
                consecutiveFailures++
                if (consecutiveFailures >= KEEPALIVE_FAILURES_BEFORE_GIVING_UP) {
                    failConnection(
                        "link lost — the drone stopped answering. Check the phone is still on " +
                            "the drone's Wi-Fi network, then connect again",
                    )
                    return
                }
            } else {
                consecutiveFailures = 0
            }
        }
    }

    /**
     * Whether a pilot is actually looking at the app.
     *
     * The keepalive exists to stop the drone auto-landing mid-flight, but that
     * failsafe is the only thing that puts the drone down if the pilot walks
     * away — pockets the phone, takes a call, goes into the bar. So the keepalive
     * is only sent while the app is in the foreground: leave, and the drone lands
     * itself about fifteen seconds later, which is what the firmware's timer is
     * for.
     *
     * Costs no permission — this is the app doing less in the background, not
     * more — and stops the process waking every five seconds in your pocket.
     *
     * Leaving while the drone is airborne also *latches* [failsafeLanding], and
     * coming back does not clear it. Returning used to resume the keepalive
     * within half a second, which the drone reads as "the pilot is back" and
     * abandons its landing for — observed leaving the aircraft with its motors
     * running at ground level, at the moment a hand reaches in for it. Once the
     * drone has been handed over, only [resumeControl] takes it back.
     */
    fun setOperatorPresent(present: Boolean) {
        if (operatorPresent == present) return
        operatorPresent = present
        if (_connection.value !is ConnectionState.Connected) return

        // Only an airborne drone has a landing to hand over. Backgrounding with
        // it parked is ordinary app switching and must stay free of charge.
        if (!present && _airborne.value) _failsafeLanding.value = true

        log(
            CommandLogEntry.Kind.INFO,
            when {
                present && _failsafeLanding.value ->
                    "app in the foreground — the drone is still landing itself, " +
                        "controls locked until you take back control"
                present -> "app in the foreground — keepalive resumed"
                else -> "app backgrounded — keepalive stopped, the drone will land itself shortly"
            },
        )
    }

    /**
     * The deliberate act that interrupts a failsafe landing: unlatches
     * [failsafeLanding], which lets the keepalive speak again on its next tick
     * and unlocks the controls.
     *
     * Sends nothing itself. The keepalive is due within [FRESHNESS_TICK_MS] and
     * goes through the same mutex as everything else, so taking control back can
     * never cut in front of a command that is already waiting for its reply.
     */
    fun resumeControl() {
        if (!_failsafeLanding.value) return
        _failsafeLanding.value = false
        log(CommandLogEntry.Kind.INFO, "pilot took back control — keepalive resumed")
    }

    // --------------------------------------------------------------- internals

    private fun openSocket(localPort: Int): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(localPort))

        val binding = socketBinder.bindToWifi(socket)
        val kind = when (binding.outcome) {
            SocketBindOutcome.BOUND, SocketBindOutcome.NOT_ATTEMPTED -> CommandLogEntry.Kind.INFO
            SocketBindOutcome.NO_WIFI_NETWORK, SocketBindOutcome.FAILED -> CommandLogEntry.Kind.ERROR
        }
        log(kind, "udp/$localPort: ${binding.detail}")
        return socket
    }

    private fun describeOpenFailure(e: Exception): String = when (e) {
        is BindException ->
            "could not bind local ports ${endpoint.localCommandPort} and " +
                "${endpoint.statePort} — another Tello app may still be running"

        is UnknownHostException -> "could not resolve ${endpoint.host}"

        else -> "could not open sockets: ${e.message ?: e.toString()}"
    }

    private fun failConnection(message: String): Boolean {
        log(CommandLogEntry.Kind.ERROR, message)
        teardown()
        _connection.value = ConnectionState.Failed(message)
        return false
    }

    private fun notConnected(command: String): TelloResponse {
        val message = "not connected — `$command` was not sent"
        log(CommandLogEntry.Kind.ERROR, message)
        return TelloResponse.Unreachable(message)
    }

    /**
     * Refuses pilot commands while the drone is landing itself, returning null
     * when there is nothing to refuse.
     *
     * The drone cannot tell one command from another: *anything* it hears resets
     * its failsafe timer and it goes back to hovering. So locking the keepalive
     * alone would only move the bug — a stray tap on the D-pad or `battery?`
     * would cancel the landing exactly as silently. Interrupting has to be one
     * deliberate act, which is [resumeControl] and nothing else.
     *
     * `land` and `emergency` are deliberately not covered: they go out through
     * [sendPriority], which never touches this path. Telling the drone to come
     * down is never the wrong thing to allow while it is coming down.
     */
    private fun refusedWhileLandingItself(command: String): TelloResponse? {
        if (!_failsafeLanding.value) return null
        val message = "the drone is landing itself — `$command` was not sent. " +
            "Take back control first, or press Land"
        log(CommandLogEntry.Kind.ERROR, message)
        return TelloResponse.Unreachable(message)
    }

    private fun teardown() {
        // Close the sockets first: a blocking receive() only returns by being
        // closed underneath, cancelling its coroutine will not wake it.
        commandSocket?.close()
        commandSocket = null
        stateSocket?.close()
        stateSocket = null
        controllerScope?.cancel()
        controllerScope = null
        replies.close()
        _state.value = null
        _telemetryFresh.value = false
        lastStateAtMillis = 0
        lastCommandAtMillis = 0
        lastPilotCommandAtMillis = 0
        lastMotorSeconds = null
        lastMotorAdvanceAtMillis = 0
        _pilotIdle.value = false
        _airborne.value = false
        // A handover belongs to one flight. The next connect starts with the
        // controls live, whatever state the last one ended in.
        _failsafeLanding.value = false
    }

    private fun log(kind: CommandLogEntry.Kind, text: String) {
        val entry = CommandLogEntry(
            id = logIds.incrementAndGet(),
            timestampMillis = clock(),
            kind = kind,
            text = text,
        )
        sessionLog?.append(entry)
        _log.update { entries ->
            val appended = entries + entry
            if (appended.size > MAX_LOG_ENTRIES) {
                appended.subList(appended.size - MAX_LOG_ENTRIES, appended.size)
            } else {
                appended
            }
        }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    companion object {
        /** Matches djitellopy's default; the drone is normally far quicker. */
        const val DEFAULT_TIMEOUT_MS = 7_000L

        /** The drone only answers `takeoff` once it is off the ground. */
        const val TAKEOFF_TIMEOUT_MS = 20_000L
        const val LAND_TIMEOUT_MS = 15_000L

        /**
         * How long to wait for a movement to be acknowledged, scaled to how far
         * it has to go.
         *
         * A flat twenty seconds was wrong, and a real flight showed why. Ordered
         * `down 50` from about 50cm up, the drone descended to its minimum
         * altitude, stopped, and then never answered at all — it hovered there
         * with the motors running while the app waited out the full timeout. The
         * pilot lost every movement control for seventeen seconds and reached
         * for the emergency cut, which is the wrong reason to press that button.
         *
         * Being generous does not cost nothing after all. The reply waiter holds
         * the send queue, so a move that hangs also silences the keepalive for
         * as long as it waits.
         *
         * Measured on that flight: 30cm took 1.5-2.2s, 100cm took 2.7s. That is
         * roughly a second of overhead plus 65cm/s. Budgeting four seconds plus
         * 25cm/s leaves better than double the margin at every distance while
         * handing control back in about five seconds rather than twenty.
         */
        const val MOVE_BASE_TIMEOUT_MS = 4_000L
        const val MOVE_MILLIS_PER_CM = 40L

        /**
         * The longest any movement may block the queue. Below the drone's own
         * failsafe, whatever that turns out to be — a hang must not be able to
         * hold the keepalive off for longer than the drone will tolerate.
         */
        const val MOVE_TIMEOUT_CEILING_MS = 15_000L

        /** Yaw is quick — a full turn is a few seconds — but give it the same floor. */
        const val TURN_MILLIS_PER_DEGREE = 25L

        /** The budget for `up|down|left|right|forward|back <distanceCm>`. */
        fun moveTimeoutMillis(distanceCm: Int): Long =
            (MOVE_BASE_TIMEOUT_MS + distanceCm.coerceAtLeast(0) * MOVE_MILLIS_PER_CM)
                .coerceAtMost(MOVE_TIMEOUT_CEILING_MS)

        /** The budget for `cw|ccw <degrees>`. */
        fun turnTimeoutMillis(degrees: Int): Long =
            (MOVE_BASE_TIMEOUT_MS + degrees.coerceAtLeast(0) * TURN_MILLIS_PER_DEGREE)
                .coerceAtMost(MOVE_TIMEOUT_CEILING_MS)

        /** Short: if the drone is not there, saying so quickly is the useful answer. */
        const val HANDSHAKE_TIMEOUT_MS = 5_000L

        /** Long enough for a just-booted drone to settle, short enough not to feel stuck. */
        const val HANDSHAKE_RETRY_DELAY_MS = 500L

        /** Telemetry arrives ~10x/sec, so 2s of silence means something is wrong. */
        const val STALE_TELEMETRY_MS = 2_000L

        /**
         * How long after the motor counter last moved the props still count as
         * turning. The counter has one-second resolution, so anything under two
         * seconds would flicker; three leaves room for a dropped packet. The
         * cost is reading "airborne" for about three seconds after touchdown,
         * which is the harmless direction.
         */
        const val MOTORS_RUNNING_WINDOW_MS = 3_000L

        /** Well inside the drone's 15s auto-land timer. */
        const val KEEPALIVE_INTERVAL_MS = 5_000L

        /**
         * Warn at twelve seconds of pilot silence. The drone's own timer is 15s
         * from the last command of any kind, so this is the last moment a
         * warning is still worth acting on.
         */
        const val IDLE_WARNING_MS = 12_000L

        /** Two silent keepalives, ~10s, still inside the drone's own 15s timer. */
        const val KEEPALIVE_FAILURES_BEFORE_GIVING_UP = 2

        /** `land` and `emergency` are idempotent, so losing one packet need not matter. */
        const val STOP_COMMAND_ATTEMPTS = 3
        const val STOP_COMMAND_SPACING_MS = 150L

        private const val FRESHNESS_TICK_MS = 500L
        private const val MAX_LOG_ENTRIES = 300
        private const val RECEIVE_BUFFER_BYTES = 2_048
    }
}
