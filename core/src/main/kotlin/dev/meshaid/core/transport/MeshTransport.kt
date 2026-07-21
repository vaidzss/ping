package dev.meshaid.core.transport

import dev.meshaid.core.protocol.NodeId

/**
 * A dumb frame pipe to nearby peers. Implementations: BLE GATT (Android/iOS),
 * in-memory links (simulator); later Nearby Connections / Wi-Fi Direct for the bulk lane.
 */
interface MeshTransport {
    fun start()
    fun stop()

    /** Send an encoded packet to every currently connected peer. */
    fun broadcast(frame: ByteArray)

    var onFrame: ((frame: ByteArray) -> Unit)?
    var onPeerConnected: ((NodeId) -> Unit)?
    var onPeerDisconnected: ((NodeId) -> Unit)?
}
