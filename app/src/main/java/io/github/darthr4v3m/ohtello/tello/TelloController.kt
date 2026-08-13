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

    /** `land`. Answers once it is down. */
    suspend fun land(): TelloResponse = sendCommand(TelloCommands.LAND, LAND_TIMEOUT_MS)

    /** `emergency` — cuts the motors immediately. The drone will drop. */
    suspend fun emergency(): TelloResponse = sendCommand(TelloCommands.EMERGENCY)

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
    suspend fun sendPriority(command: String) {
        val socket = commandSocket ?: run {
            notConnected(command)
            return
        }
        withContext(ioDispatcher) {
            try {
                writeDatagram(socket, command)
                lastCommandAtMillis = clock()
                log(CommandLogEntry.Kind.SENT, "→ $command (jumped the queue)")
            } catch (e: IOException) {
                log(CommandLogEntry.Kind.ERROR, "could not send `$command`: ${e.message}")
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
        while (currentCoroutineContext().isActive) {
            delay(FRESHNESS_TICK_MS)
            if (_connection.value !is ConnectionState.Connected) continue
            if (clock() - lastCommandAtMillis < KEEPALIVE_INTERVAL_MS) continue
            send(TelloCommands.ENTER_SDK_MODE, HANDSHAKE_TIMEOUT_MS, CommandLogEntry.Kind.KEEPALIVE)
        }
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

        private const val FRESHNESS_TICK_MS = 500L
        private const val MAX_LOG_ENTRIES = 300
        private const val RECEIVE_BUFFER_BYTES = 2_048
    }
}
