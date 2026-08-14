package io.github.darthr4v3m.ohtello.tello

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * A drone-shaped UDP echo on loopback, enough to exercise the controller's
 * send/receive contract without a real Tello: it answers commands on its
 * command port and can push state packets at the controller's state port.
 */
class FakeDrone(
    private val commandPort: Int,
    private val controllerStatePort: Int,
) {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val socket = DatagramSocket(InetSocketAddress(loopback, commandPort))
    private val stateSender = DatagramSocket()

    /** Every command received, in arrival order. */
    val received: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /** Milliseconds since the drone started, per command, for ordering assertions. */
    val receivedAtMillis: MutableList<Long> = Collections.synchronizedList(mutableListOf<Long>())

    /**
     * Decides the reply for a command. Return null to stay silent, as the real
     * drone does for `rc`. Runs on the drone's own thread, so a sleep in here
     * models a slow drone.
     */
    @Volatile
    var responder: (String) -> String? = { "ok" }

    /**
     * Replies with raw bytes instead of text, for the binary packets a real
     * Tello also emits on the command port. Wins over [responder] whenever it
     * returns something.
     */
    @Volatile
    var rawResponder: (String) -> ByteArray? = { null }

    private val started = CountDownLatch(1)
    private var thread: Thread? = null

    fun start() {
        thread = Thread {
            started.countDown()
            val buffer = ByteArray(1024)
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketException) {
                    return@Thread
                }
                val command = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                received += command
                receivedAtMillis += System.currentTimeMillis()

                val payload = rawResponder(command)
                    ?: responder(command)?.toByteArray(Charsets.US_ASCII)
                    ?: continue
                try {
                    socket.send(DatagramPacket(payload, payload.size, packet.address, packet.port))
                } catch (e: SocketException) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }
        started.await()
    }

    /** Pushes one telemetry packet at the controller's state port. */
    fun pushState(text: String) {
        val payload = text.toByteArray(Charsets.US_ASCII)
        stateSender.send(DatagramPacket(payload, payload.size, loopback, controllerStatePort))
    }

    fun close() {
        socket.close()
        stateSender.close()
        thread?.join(1_000)
    }

    companion object {
        /** Loopback ports for a test, picked by the OS and immediately released. */
        fun freePorts(count: Int): List<Int> {
            val sockets = List(count) { DatagramSocket(0) }
            val ports = sockets.map { it.localPort }
            sockets.forEach { it.close() }
            return ports
        }
    }
}
