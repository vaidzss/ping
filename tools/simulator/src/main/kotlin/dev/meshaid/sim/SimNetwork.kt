package dev.meshaid.sim

import dev.meshaid.core.MeshNode
import dev.meshaid.core.protocol.NodeId
import dev.meshaid.core.protocol.Packet
import dev.meshaid.core.transport.MeshTransport
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Deterministic virtual-time mesh simulator: dozens of nodes with positions, radio range,
 * and lossy links, running the real MeshNode/MeshRouter code. One tick = one hop latency.
 */
class SimNetwork(
    seed: Long,
    val rangeM: Double = 30.0,
    val lossProb: Double = 0.0,
    val tickMs: Long = 50,
) {
    var nowMs: Long = 1_000_000L
        private set
    private val rng = Random(seed)
    val nodes = mutableListOf<SimNode>()
    private var pending = ArrayList<Transmission>()
    var totalTransmissions = 0
        private set

    private class Transmission(val from: SimNode, val frame: ByteArray)

    fun addNode(idRaw: Long, x: Double, y: Double): SimNode {
        val node = SimNode(this, NodeId(idRaw), x, y)
        nodes.add(node)
        return node
    }

    internal fun enqueue(from: SimNode, frame: ByteArray) {
        totalTransmissions++
        pending.add(Transmission(from, frame))
    }

    fun tick() {
        nowMs += tickMs
        updateAdjacency()
        val deliveries = pending
        pending = ArrayList()
        for (t in deliveries) {
            for (n in nodes) {
                if (n === t.from) continue
                if (inRange(n, t.from) && rng.nextDouble() >= lossProb) {
                    n.transport.deliver(t.frame)
                }
            }
        }
    }

    fun run(ticks: Int) = repeat(ticks) { tick() }

    /** Random-walk churn: every node moves up to [maxStep] in each axis. */
    fun moveAll(maxStep: Double, moveRng: Random) {
        for (n in nodes) {
            n.x += moveRng.nextDouble(-maxStep, maxStep)
            n.y += moveRng.nextDouble(-maxStep, maxStep)
        }
    }

    private fun inRange(a: SimNode, b: SimNode): Boolean = hypot(a.x - b.x, a.y - b.y) <= rangeM

    private fun updateAdjacency() {
        for (n in nodes) {
            val visible = nodes.asSequence()
                .filter { it !== n && inRange(n, it) }
                .map { it.id }
                .toSet()
            n.transport.updatePeers(visible)
        }
    }
}

class SimNode(
    internal val network: SimNetwork,
    val id: NodeId,
    var x: Double,
    var y: Double,
) {
    val transport = SimTransport(this)
    val meshNode = MeshNode(id, { network.nowMs }, transport)
    val received = mutableListOf<Packet>()

    init {
        meshNode.onMessage = { received.add(it) }
    }
}

class SimTransport(private val node: SimNode) : MeshTransport {
    override var onFrame: ((ByteArray) -> Unit)? = null
    override var onPeerConnected: ((NodeId) -> Unit)? = null
    override var onPeerDisconnected: ((NodeId) -> Unit)? = null

    private val connected = mutableSetOf<NodeId>()

    override fun start() {}
    override fun stop() {}

    override fun broadcast(frame: ByteArray) {
        node.network.enqueue(node, frame)
    }

    internal fun deliver(frame: ByteArray) {
        onFrame?.invoke(frame)
    }

    internal fun updatePeers(visible: Set<NodeId>) {
        (visible - connected).forEach { onPeerConnected?.invoke(it) }
        (connected - visible).forEach { onPeerDisconnected?.invoke(it) }
        connected.clear()
        connected.addAll(visible)
    }
}
