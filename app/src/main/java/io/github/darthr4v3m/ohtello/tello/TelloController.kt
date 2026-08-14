package io.github.darthr4v3m.ohtello.tello

import io.github.darthr4v3m.ohtello.tello.protocol.MoveDirection
import io.github.darthr4v3m.ohtello.tello.protocol.TelloCommands
import io.github.darthr4v3m.ohtello.tello.protocol.TelloResponse
import io.github.darthr4v3m.ohtello.tello.protocol.TelloState
import io.github.darthr4v3m.ohtello.tello.protocol.TurnDirection
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

    private val commandMutex = Mutex()
    private val logIds = AtomicLong(0)

    @Volatile private var commandSocket: DatagramSocket? = null
    @Volatile private var stateSocket: DatagramSocket? = null
    @Volatile private var controllerScope: CoroutineScope? = null
    @Volatile private var replies: Channel<String> = Channel(Channel.UNLIMITED)
    @Volatile private var lastCommandAtMillis: Long = 0
    @Volatile private var operatorPresent: Boolean = true
    @Volatile private var lastStateAtMillis: Long = 0

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

        when (val response = sendCommand(TelloCommands.ENTER_SDK_MODE, HANDSHAKE_TIMEOUT_MS)) {
            is TelloResponse.Ok -> {
                _connection.value = ConnectionState.Connected
                log(CommandLogEntry.Kind.INFO, "SDK mode entered")
                scope.launch { sendKeepAlives() }
                true
            }

            is TelloResponse.Timeout -> failConnection(
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
        sendCommand(TelloCommands.move(direction, distanceCm), MOVE_TIMEOUT_MS)

    /** Discrete `cw|ccw <degrees>`; angle is clamped. */
    suspend fun turn(direction: TurnDirection, degrees: Int): TelloResponse =
        sendCommand(TelloCommands.turn(direction, degrees), MOVE_TIMEOUT_MS)

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

                try {
                    writeDatagram(socket, command)
                } catch (e: IOException) {
                    val message = "could not send `$command`: ${e.message ?: e.toString()}"
                    log(CommandLogEntry.Kind.ERROR, message)
                    return@withLock TelloResponse.Failure("", message)
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
        }
    }

    private suspend fun trackTelemetryFreshness() {
        while (currentCoroutineContext().isActive) {
            val last = lastStateAtMillis
            _telemetryFresh.value = last != 0L && clock() - last < STALE_TELEMETRY_MS
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
        var consecutiveTimeouts = 0
        while (currentCoroutineContext().isActive) {
            delay(FRESHNESS_TICK_MS)
            if (_connection.value !is ConnectionState.Connected) continue

            // With nobody watching the screen, the right thing is to stop
            // talking and let the drone's own failsafe put it down. See
            // [operatorPresent].
            if (!operatorPresent) continue

            if (clock() - lastCommandAtMillis < KEEPALIVE_INTERVAL_MS) continue

            val response =
                send(TelloCommands.ENTER_SDK_MODE, HANDSHAKE_TIMEOUT_MS, CommandLogEntry.Kind.KEEPALIVE)

            // Nothing else ever moves the link out of Connected: a command that
            // fails logs an error and leaves the UI claiming all is well, and a
            // socket pinned to a Wi-Fi network that has gone away stays dead
            // even after the phone rejoins. Two silent keepalives is ~10s, still
            // inside the drone's own 15s timer, and tearing down here means the
            // next Connect binds to the current network rather than the corpse.
            if (response is TelloResponse.Timeout) {
                consecutiveTimeouts++
                if (consecutiveTimeouts >= KEEPALIVE_FAILURES_BEFORE_GIVING_UP) {
                    failConnection(
                        "link lost — the drone stopped answering. Check the phone is still on " +
                            "the drone's Wi-Fi network, then connect again",
                    )
                    return
                }
            } else {
                consecutiveTimeouts = 0
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
     */
    fun setOperatorPresent(present: Boolean) {
        if (operatorPresent == present) return
        operatorPresent = present
        if (_connection.value !is ConnectionState.Connected) return
        log(
            CommandLogEntry.Kind.INFO,
            if (present) {
                "app in the foreground — keepalive resumed"
            } else {
                "app backgrounded — keepalive stopped, the drone will land itself in about 15s"
            },
        )
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
        return TelloResponse.Failure("", message)
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
         * A long `move` at a slow speed setting can take a while, and a timeout
         * here does not stop the drone — it only means we stopped listening, so
         * being generous costs nothing.
         */
        const val MOVE_TIMEOUT_MS = 20_000L

        /** Short: if the drone is not there, saying so quickly is the useful answer. */
        const val HANDSHAKE_TIMEOUT_MS = 5_000L

        /** Telemetry arrives ~10x/sec, so 2s of silence means something is wrong. */
        const val STALE_TELEMETRY_MS = 2_000L

        /** Well inside the drone's 15s auto-land timer. */
        const val KEEPALIVE_INTERVAL_MS = 5_000L

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
