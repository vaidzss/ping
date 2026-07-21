package dev.meshaid.core.transport

import dev.meshaid.core.protocol.NodeId

/**
 * The "rainbow" stack: one logical transport fanning out over several radios at once
 * (BLE + LAN today; Nearby/Wi-Fi Aware later). Dedup in the router makes receiving the
 * same frame over two lanes harmless.
 */
class CompositeMeshTransport(private val lanes: List<MeshTransport>) : MeshTransport {

    override var onFrame: ((ByteArray) -> Unit)? = null
    override var onPeerConnected: ((NodeId) -> Unit)? = null
    override var onPeerDisconnected: ((NodeId) -> Unit)? = null

    init {
        for (lane in lanes) {
            lane.onFrame = { onFrame?.invoke(it) }
            lane.onPeerConnected = { onPeerConnected?.invoke(it) }
            lane.onPeerDisconnected = { onPeerDisconnected?.invoke(it) }
        }
    }

    override fun start() = lanes.forEach { it.start() }
    override fun stop() = lanes.forEach { it.stop() }
    override fun broadcast(frame: ByteArray) = lanes.forEach { it.broadcast(frame) }
}
