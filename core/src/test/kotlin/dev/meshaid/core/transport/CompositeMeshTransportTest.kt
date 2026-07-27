package dev.meshaid.core.transport

import dev.meshaid.core.protocol.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositeMeshTransportTest {

    private class FakeTransport(
        private val failStart: Boolean = false,
        private val failStop: Boolean = false,
        private val failBroadcast: Boolean = false,
    ) : MeshTransport {
        override var onFrame: ((ByteArray) -> Unit)? = null
        override var onPeerConnected: ((NodeId) -> Unit)? = null
        override var onPeerDisconnected: ((NodeId) -> Unit)? = null

        var started = false
        var stopped = false
        val broadcasted = mutableListOf<ByteArray>()

        override fun start() {
            if (failStart) error("start failed")
            started = true
        }

        override fun stop() {
            if (failStop) error("stop failed")
            stopped = true
        }

        override fun broadcast(frame: ByteArray) {
            if (failBroadcast) error("broadcast failed")
            broadcasted.add(frame)
        }
    }

    @Test
    fun `start stop and broadcast fan out to every lane`() {
        val a = FakeTransport()
        val b = FakeTransport()
        val composite = CompositeMeshTransport(listOf(a, b))

        composite.start()
        assertTrue(a.started && b.started)

        composite.broadcast("hello".toByteArray())
        assertEquals(1, a.broadcasted.size)
        assertEquals(1, b.broadcasted.size)

        composite.stop()
        assertTrue(a.stopped && b.stopped)
    }

    @Test
    fun `frames and peer events from any lane surface through the composite callbacks`() {
        val a = FakeTransport()
        val b = FakeTransport()
        val composite = CompositeMeshTransport(listOf(a, b))

        val seenFrames = mutableListOf<String>()
        composite.onFrame = { seenFrames.add(String(it)) }
        val connected = mutableListOf<NodeId>()
        composite.onPeerConnected = { connected.add(it) }
        val disconnected = mutableListOf<NodeId>()
        composite.onPeerDisconnected = { disconnected.add(it) }

        a.onFrame?.invoke("from-a".toByteArray())
        b.onFrame?.invoke("from-b".toByteArray())
        assertEquals(listOf("from-a", "from-b"), seenFrames)

        a.onPeerConnected?.invoke(NodeId(1L))
        b.onPeerConnected?.invoke(NodeId(2L))
        assertEquals(listOf(NodeId(1L), NodeId(2L)), connected)

        a.onPeerDisconnected?.invoke(NodeId(1L))
        assertEquals(listOf(NodeId(1L)), disconnected)
    }

    @Test
    fun `one lane failing to start does not block the others from starting`() {
        val broken = FakeTransport(failStart = true)
        val healthy = FakeTransport()
        val composite = CompositeMeshTransport(listOf(broken, healthy))

        val diagnostics = mutableListOf<String>()
        composite.onDiagnostic = { diagnostics.add(it) }

        composite.start()

        assertTrue(healthy.started, "the healthy lane must still start even though the first lane threw")
        assertEquals(1, diagnostics.size)
        assertTrue(diagnostics[0].contains("start"))
    }

    @Test
    fun `one lane failing to stop does not block the others from stopping`() {
        val healthy = FakeTransport()
        val broken = FakeTransport(failStop = true)
        val composite = CompositeMeshTransport(listOf(broken, healthy))

        composite.stop()

        assertTrue(healthy.stopped, "a lane after a throwing one must still be stopped, or its resources leak")
    }

    @Test
    fun `one lane failing to broadcast does not block the others from receiving the frame`() {
        val broken = FakeTransport(failBroadcast = true)
        val healthy = FakeTransport()
        val composite = CompositeMeshTransport(listOf(broken, healthy))

        composite.broadcast("hello".toByteArray())

        assertEquals(1, healthy.broadcasted.size)
    }
}
