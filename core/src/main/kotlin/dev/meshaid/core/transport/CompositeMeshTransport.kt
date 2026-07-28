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

    /**
     * One lane throwing (BLE adapter off, LAN socket bind failure) used to take every other
     * lane down with it — `lanes.forEach { it.start() }` never reaches lane 2 if lane 1
     * throws. Each lane's start/stop/broadcast is now isolated; this surfaces which one and why.
     */
    var onDiagnostic: ((String) -> Unit)? = null

    init {
        for (lane in lanes) {
            lane.onFrame = { onFrame?.invoke(it) }
            lane.onPeerConnected = { onPeerConnected?.invoke(it) }
            lane.onPeerDisconnected = { onPeerDisconnected?.invoke(it) }
        }
    }

    override fun start() = forEachLane("start") { it.start() }
    override fun stop() = forEachLane("stop") { it.stop() }
    override fun broadcast(frame: ByteArray) = forEachLane("broadcast") { it.broadcast(frame) }

    private inline fun forEachLane(action: String, block: (MeshTransport) -> Unit) {
        for ((index, lane) in lanes.withIndex()) {
            try {
                block(lane)
            } catch (e: Exception) {
                onDiagnostic?.invoke(
                    "lane $index (${lane.javaClass.simpleName}) $action failed (${e.javaClass.simpleName}: ${e.message})",
                )
            }
        }
    }
}
