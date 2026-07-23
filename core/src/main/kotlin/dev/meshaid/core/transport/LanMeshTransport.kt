package dev.meshaid.core.transport

import dev.meshaid.core.protocol.NodeId
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * LAN lane: peers on the same Wi-Fi network / phone hotspot (still zero internet).
 * Discovery over UDP multicast beacons; frames over TCP, length-prefixed. Pure JVM —
 * shared by the Android app and the desktop node.
 *
 * Beacon: "MAID"(4) | nodeId(8) | tcpPort(u16), every 3 s.
 * Dial rule mirrors BLE: the numerically smaller NodeId initiates the TCP connection.
 * Frame stream: peer's nodeId(8) once at connect, then [len(u16) | frame] repeated.
 */
class LanMeshTransport(
    private val selfId: NodeId,
    private val multicastGroup: String = DEFAULT_GROUP,
    private val multicastPort: Int = DEFAULT_PORT,
    private val enableDiscovery: Boolean = true,
) : MeshTransport {

    companion object {
        const val DEFAULT_GROUP = "239.77.83.72"
        const val DEFAULT_PORT = 47474
        private const val BEACON_INTERVAL_MS = 3_000L
        private val MAGIC = "MAID".toByteArray()
        private const val MAX_FRAME = 65_535
    }

    override var onFrame: ((ByteArray) -> Unit)? = null
    override var onPeerConnected: ((NodeId) -> Unit)? = null
    override var onPeerDisconnected: ((NodeId) -> Unit)? = null

    /**
     * Transport-level diagnostics with no equivalent in the generic MeshTransport
     * interface: failed dial, failed handshake, a write that killed the link. All of
     * these previously failed via a bare `catch (_: Exception) {}` with zero visibility.
     */
    var onDiagnostic: ((String) -> Unit)? = null

    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private var beaconSocket: MulticastSocket? = null
    private val peers = ConcurrentHashMap<NodeId, PeerLink>()
    private val threads = mutableListOf<Thread>()

    val port: Int get() = server?.localPort ?: -1
    fun peerCount(): Int = peers.size

    private val lastDialMs = ConcurrentHashMap<NodeId, Long>()
    private val firstBeaconMs = ConcurrentHashMap<NodeId, Long>()

    /** IPv4 addresses of every interface discovery runs on (diagnostics). */
    fun localAddresses(): List<String> = eligibleInterfaces().flatMap { ni ->
        ni.interfaceAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress }
    }

    private fun eligibleInterfaces(): List<NetworkInterface> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { ni ->
                runCatching {
                    ni.isUp && !ni.isLoopback && ni.supportsMulticast() &&
                        ni.interfaceAddresses.any { it.address is Inet4Address }
                }.getOrDefault(false)
            }
        }.getOrDefault(emptyList())

    private inner class PeerLink(val id: NodeId, val socket: Socket) {
        val out = DataOutputStream(socket.getOutputStream().buffered())

        fun sendFrame(frame: ByteArray) {
            if (frame.size > MAX_FRAME) {
                onDiagnostic?.invoke("frame to $id too large for LAN (${frame.size} > $MAX_FRAME), dropped")
                return
            }
            try {
                synchronized(out) {
                    out.writeShort(frame.size)
                    out.write(frame)
                    out.flush()
                }
            } catch (e: Exception) {
                onDiagnostic?.invoke("write to $id failed (${e.javaClass.simpleName}: ${e.message}), link dropped")
                drop(this)
            }
        }
    }

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        val srv = ServerSocket()
        srv.reuseAddress = true
        srv.bind(InetSocketAddress(0))
        server = srv
        thread("lan-accept") {
            while (running) {
                val socket = try {
                    srv.accept()
                } catch (_: Exception) {
                    break
                }
                thread("lan-peer-in") { handleInbound(socket) }
            }
        }
        if (enableDiscovery) startDiscovery()
    }

    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        runCatching { beaconSocket?.close() }
        runCatching { server?.close() }
        peers.values.forEach { runCatching { it.socket.close() } }
        peers.clear()
        threads.forEach { it.interrupt() }
        threads.clear()
    }

    override fun broadcast(frame: ByteArray) {
        peers.values.forEach { it.sendFrame(frame) }
    }

    /** Direct connect — used by tests and for manually-entered peer addresses. */
    fun connectTo(host: String, tcpPort: Int) {
        thread("lan-dial") {
            runCatching {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, tcpPort), 5_000)
                handshakeAndRun(socket, initiator = true)
            }.onFailure { e ->
                onDiagnostic?.invoke("dial to $host:$tcpPort failed (${e.javaClass.simpleName}: ${e.message})")
            }
        }
    }

    // ---------------------------------------------------------------- discovery

    private fun startDiscovery() {
        val group = InetAddress.getByName(multicastGroup)
        val socket = MulticastSocket(multicastPort)
        // Join on EVERY eligible interface: with virtual adapters (Hyper-V/WSL/VPN) around,
        // the OS default interface is often the wrong one for the hotspot link.
        val joined = eligibleInterfaces().filter { ni ->
            runCatching { socket.joinGroup(InetSocketAddress(group, multicastPort), ni) }.isSuccess
        }
        if (joined.isEmpty()) runCatching { socket.joinGroup(InetSocketAddress(group, multicastPort), null) }
        beaconSocket = socket

        thread("lan-beacon-tx") {
            while (running) {
                val payload = ByteBuffer.allocate(MAGIC.size + 8 + 2)
                    .put(MAGIC)
                    .putLong(selfId.raw)
                    .putShort(port.toShort())
                    .array()
                val packet = DatagramPacket(payload, payload.size, group, multicastPort)
                // Beacon out of every interface, not just the default route.
                for (ni in eligibleInterfaces()) {
                    runCatching {
                        synchronized(socket) {
                            socket.networkInterface = ni
                            socket.send(packet)
                        }
                    }
                }
                try {
                    Thread.sleep(BEACON_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        thread("lan-beacon-rx") {
            val buf = ByteArray(64)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    break
                }
                if (packet.length < MAGIC.size + 10) continue
                val bb = ByteBuffer.wrap(packet.data, packet.offset, packet.length)
                val magic = ByteArray(4).also { bb.get(it) }
                if (!magic.contentEquals(MAGIC)) continue
                val peerId = NodeId(bb.long)
                val peerPort = bb.short.toInt() and 0xFFFF
                if (peerId == selfId || peers.containsKey(peerId)) continue
                val host = packet.address.hostAddress ?: continue
                maybeDial(peerId, host, peerPort)
            }
        }
    }

    /**
     * Dial policy: the smaller id dials immediately (one link per pair). But if we're the
     * larger id and the peer's inbound dial never lands (host firewalls commonly block
     * inbound), fall back to dialing them after a short grace period — outbound usually
     * survives firewalls. Duplicate links resolve in the handshake (putIfAbsent).
     */
    private fun maybeDial(peerId: NodeId, host: String, peerPort: Int) {
        val now = System.currentTimeMillis()
        val weDialFirst = selfId.raw.toULong() < peerId.raw.toULong()
        val last = lastDialMs[peerId] ?: 0L
        if (weDialFirst) {
            if (now - last < 5_000) return
        } else {
            val firstSeen = firstBeaconMs.getOrPut(peerId) { now }
            if (now - firstSeen < 4_000) return // give their dial a chance first
            if (now - last < 10_000) return
        }
        lastDialMs[peerId] = now
        connectTo(host, peerPort)
    }

    // ---------------------------------------------------------------- link lifecycle

    private fun handleInbound(socket: Socket) {
        runCatching { handshakeAndRun(socket, initiator = false) }
            .onFailure { e ->
                onDiagnostic?.invoke("inbound handshake from ${socket.inetAddress?.hostAddress} failed (${e.javaClass.simpleName}: ${e.message})")
            }
    }

    private fun handshakeAndRun(socket: Socket, initiator: Boolean) {
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream().buffered())
        val out = DataOutputStream(socket.getOutputStream())
        // Both sides state their NodeId first.
        out.writeLong(selfId.raw)
        out.flush()
        val peerId = NodeId(input.readLong())
        if (peerId == selfId) {
            socket.close()
            return
        }
        val link = PeerLink(peerId, socket)
        val existing = peers.putIfAbsent(peerId, link)
        if (existing != null) {
            onDiagnostic?.invoke("duplicate connection to $peerId (${if (initiator) "outbound" else "inbound"}) closed, existing link kept")
            socket.close()
            return
        }
        onPeerConnected?.invoke(peerId)
        try {
            while (running && !socket.isClosed) {
                val len = input.readUnsignedShort()
                if (len == 0 || len > MAX_FRAME) break
                val frame = ByteArray(len)
                input.readFully(frame)
                onFrame?.invoke(frame)
            }
        } catch (e: Exception) {
            onDiagnostic?.invoke("read loop for $peerId ended (${e.javaClass.simpleName}: ${e.message})")
        }
        drop(link)
    }

    private fun drop(link: PeerLink) {
        if (peers.remove(link.id, link)) {
            runCatching { link.socket.close() }
            firstBeaconMs.remove(link.id) // restart the dial grace period on reconnect
            onPeerDisconnected?.invoke(link.id)
        }
    }

    private fun thread(name: String, body: () -> Unit) {
        val t = Thread(body, name)
        t.isDaemon = true
        synchronized(threads) { threads.add(t) }
        t.start()
    }
}
