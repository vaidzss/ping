package dev.meshaid.core

import dev.meshaid.core.mesh.MeshConfig
import dev.meshaid.core.mesh.MeshRouter
import dev.meshaid.core.protocol.NodeId
import dev.meshaid.core.protocol.Packet
import dev.meshaid.core.protocol.PacketCodec
import dev.meshaid.core.protocol.PacketType
import dev.meshaid.core.protocol.ProtocolException
import dev.meshaid.core.transport.MeshTransport

/**
 * Glue between a transport and the router: one running mesh participant.
 * Used verbatim by the Android service and by every simulator node.
 */
class MeshNode(
    val selfId: NodeId,
    private val clock: () -> Long,
    private val transport: MeshTransport,
    config: MeshConfig = MeshConfig(),
) {
    val router = MeshRouter(selfId, clock, config)

    /** Delivered application messages (deduped, addressed to us or broadcast). */
    var onMessage: ((Packet) -> Unit)? = null

    /** Diagnostics: every frame this node puts on the air (send + relay). */
    var onTransmit: ((Packet) -> Unit)? = null

    init {
        transport.onFrame = ::receiveFrame
        transport.onPeerConnected = { router.neighborUp(it) }
        transport.onPeerDisconnected = { router.neighborDown(it) }
    }

    fun start() = transport.start()
    fun stop() = transport.stop()

    fun send(
        type: PacketType,
        payload: ByteArray,
        recipientId: NodeId? = null,
        sign: ((ByteArray) -> ByteArray)? = null,
        encrypted: Boolean = false,
    ): Packet {
        var packet = router.prepareOutbound(type, payload, recipientId, encrypted)
        sign?.let { packet = packet.withSignature(it(PacketCodec.signingBytes(packet))) }
        transmit(packet)
        return packet
    }

    private fun receiveFrame(frame: ByteArray) {
        val packet = try {
            PacketCodec.decode(frame)
        } catch (_: ProtocolException) {
            return // malformed frames are dropped silently; the mesh must not be crashable
        } catch (_: IllegalArgumentException) {
            return
        }
        val result = router.onReceive(packet)
        if (result.deliver) onMessage?.invoke(packet)
        result.relay?.let { transmit(it) }
    }

    private fun transmit(packet: Packet) {
        onTransmit?.invoke(packet)
        transport.broadcast(PacketCodec.encode(packet))
    }
}
