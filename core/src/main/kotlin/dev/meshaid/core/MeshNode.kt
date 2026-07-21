package dev.meshaid.core

import dev.meshaid.core.blob.BlobStore
import dev.meshaid.core.dtn.Bundle
import dev.meshaid.core.dtn.BundlePriority
import dev.meshaid.core.dtn.BundleStore
import dev.meshaid.core.media.IncomingTransfer
import dev.meshaid.core.media.MediaCodecs
import dev.meshaid.core.media.MediaOffer
import dev.meshaid.core.mesh.MeshConfig
import dev.meshaid.core.mesh.MeshRouter
import dev.meshaid.core.protocol.NodeId
import dev.meshaid.core.protocol.Packet
import dev.meshaid.core.protocol.PacketCodec
import dev.meshaid.core.protocol.PacketType
import dev.meshaid.core.protocol.ProtocolException
import dev.meshaid.core.protocol.SummaryVector

/**
 * Glue between a transport and the router: one running mesh participant.
 * Used verbatim by the Android service and by every simulator node.
 *
 * Beyond plain flooding it wires in:
 * - presence: TTL-1 beacons announce direct neighbors (and their display names), with expiry;
 * - DTN sync: on contact, peers exchange summary vectors and pull missing bundles
 *   (store-carry-forward — every phone is a data mule);
 * - media: offer/request/chunk transfer of blobs, verified against their content hash.
 */
class MeshNode(
    val selfId: NodeId,
    private val clock: () -> Long,
    private val transport: dev.meshaid.core.transport.MeshTransport,
    config: MeshConfig = MeshConfig(),
    private val bundleStore: BundleStore? = null,
    private val blobStore: BlobStore? = null,
) {
    companion object {
        const val PRESENCE_TIMEOUT_MS = 35_000L
        const val SYNC_INTERVAL_MS = 30_000L
        const val MAX_AUTO_FETCH_BYTES = 1 shl 20 // control-lane media cap: 1 MiB
    }

    val router = MeshRouter(selfId, clock, config)

    /** Delivered application messages (CHAT / SOS / GPS_BEACON). */
    var onMessage: ((Packet) -> Unit)? = null

    /** Diagnostics: every frame this node puts on the air (send + relay). */
    var onTransmit: ((Packet) -> Unit)? = null

    /** A direct neighbor announced itself (nodeId, displayName). */
    var onPeerPresence: ((NodeId, String) -> Unit)? = null

    /** A media offer arrived. Return true to fetch the blob. */
    var onMediaOffer: ((MediaOffer, NodeId) -> Boolean)? = null

    /** A fetched blob passed hash verification and is now in the blob store. */
    var onMediaReceived: ((hashHex: String, mimeTag: Int, from: NodeId) -> Unit)? = null

    private val presenceSeen = HashMap<NodeId, Long>()
    private val lastSync = HashMap<NodeId, Long>()
    private val incoming = HashMap<String, Pair<IncomingTransfer, NodeId>>()

    init {
        transport.onFrame = ::receiveFrame
        transport.onPeerConnected = ::peerUp
        transport.onPeerDisconnected = { router.neighborDown(it) }
    }

    fun start() = transport.start()
    fun stop() = transport.stop()

    // ---------------------------------------------------------------- sending

    fun send(
        type: PacketType,
        payload: ByteArray,
        recipientId: NodeId? = null,
        sign: ((ByteArray) -> ByteArray)? = null,
        encrypted: Boolean = false,
        ttl: Int? = null,
    ): Packet {
        var packet = router.prepareOutbound(type, payload, recipientId, encrypted, ttl)
        sign?.let { packet = packet.withSignature(it(PacketCodec.signingBytes(packet))) }
        storeAsBundle(packet)
        transmit(packet)
        return packet
    }

    /** Announce ourselves to direct neighbors only (TTL 1 — never relayed). */
    fun sendPresence(displayName: String) {
        send(PacketType.PRESENCE, displayName.toByteArray(), ttl = 1)
    }

    /** Put a blob in the store and announce it to the mesh. Returns its content hash. */
    fun offerMedia(data: ByteArray, mimeTag: Int): String {
        val store = blobStore ?: error("MeshNode has no blob store")
        require(data.size <= MAX_AUTO_FETCH_BYTES) { "control-lane media capped at 1 MiB — use the bulk lane" }
        val ref = store.put(data)
        val offer = MediaOffer(
            blobHash = BlobStore.sha256(data),
            totalSize = data.size,
            mimeTag = mimeTag,
            chunkSize = MediaCodecs.DEFAULT_CHUNK_SIZE,
        )
        send(PacketType.MEDIA_OFFER, MediaCodecs.encodeOffer(offer))
        return ref.hashHex
    }

    /** Expire silent neighbors. Call periodically (the service/simulator heartbeat). */
    fun tick() {
        val now = clock()
        val expired = synchronized(presenceSeen) {
            val gone = presenceSeen.filterValues { now - it > PRESENCE_TIMEOUT_MS }.keys.toList()
            gone.forEach { presenceSeen.remove(it) }
            gone
        }
        expired.forEach { router.neighborDown(it) }
    }

    // ---------------------------------------------------------------- receiving

    private fun receiveFrame(frame: ByteArray) {
        val packet = try {
            PacketCodec.decode(frame)
        } catch (_: ProtocolException) {
            return // malformed frames are dropped silently; the mesh must not be crashable
        } catch (_: IllegalArgumentException) {
            return
        }
        val result = router.onReceive(packet)
        if (result.deliver) dispatch(packet)
        result.relay?.let {
            storeAsBundle(packet)
            transmit(it)
        }
    }

    private fun dispatch(packet: Packet) {
        when (packet.type) {
            PacketType.PRESENCE -> {
                peerUp(packet.senderId)
                onPeerPresence?.invoke(packet.senderId, String(packet.payload))
            }
            PacketType.SUMMARY_VECTOR -> handleSummaryVector(packet)
            PacketType.BUNDLE_PULL -> handleBundlePull(packet)
            PacketType.MEDIA_OFFER -> handleMediaOffer(packet)
            PacketType.MEDIA_REQUEST -> handleMediaRequest(packet)
            PacketType.MEDIA_CHUNK -> handleMediaChunk(packet)
            else -> {
                storeAsBundle(packet)
                onMessage?.invoke(packet)
            }
        }
    }

    private fun peerUp(id: NodeId) {
        if (id == selfId) return
        router.neighborUp(id)
        synchronized(presenceSeen) { presenceSeen[id] = clock() }
        maybeSyncWith(id)
    }

    // ---------------------------------------------------------------- DTN sync

    private fun storeAsBundle(packet: Packet) {
        val store = bundleStore ?: return
        if (!packet.isBroadcast) return
        if (packet.type != PacketType.CHAT && packet.type != PacketType.SOS) return
        store.add(
            Bundle(
                id = PacketCodec.messageId(packet),
                encodedPacket = PacketCodec.encode(packet),
                createdAtMs = clock(),
                priority = if (packet.type == PacketType.SOS) BundlePriority.SOS else BundlePriority.NORMAL,
            ),
        )
    }

    private fun maybeSyncWith(peer: NodeId) {
        val store = bundleStore ?: return
        val now = clock()
        synchronized(lastSync) {
            val last = lastSync[peer]
            if (last != null && now - last < SYNC_INTERVAL_MS) return
            lastSync[peer] = now
        }
        val vector = store.summaryVector()
        if (vector.isEmpty()) return
        vector.chunked(SummaryVector.MAX_IDS).forEach { chunk ->
            send(PacketType.SUMMARY_VECTOR, SummaryVector.encode(chunk), recipientId = peer, ttl = 1)
        }
    }

    private fun handleSummaryVector(packet: Packet) {
        val store = bundleStore ?: return
        val remoteIds = try {
            SummaryVector.decode(packet.payload)
        } catch (_: ProtocolException) {
            return
        }
        val missing = store.missingFrom(remoteIds)
        if (missing.isEmpty()) return
        missing.chunked(SummaryVector.MAX_IDS).forEach { chunk ->
            send(PacketType.BUNDLE_PULL, SummaryVector.encode(chunk), recipientId = packet.senderId, ttl = 1)
        }
    }

    private fun handleBundlePull(packet: Packet) {
        val store = bundleStore ?: return
        val requested = try {
            SummaryVector.decode(packet.payload)
        } catch (_: ProtocolException) {
            return
        }
        // Re-inject the stored frames: the puller (a direct neighbor) processes them as
        // ordinary packets, delivering and relaying onward per normal mesh rules.
        store.takeForSpray(requested).forEach { transport.broadcast(it.encodedPacket) }
    }

    // ---------------------------------------------------------------- media exchange

    private fun handleMediaOffer(packet: Packet) {
        val store = blobStore ?: return
        val offer = try {
            MediaCodecs.decodeOffer(packet.payload)
        } catch (_: ProtocolException) {
            return
        }
        if (offer.totalSize > MAX_AUTO_FETCH_BYTES) return
        if (store.has(offer.hashHex)) return
        synchronized(incoming) {
            if (incoming.containsKey(offer.hashHex)) return
        }
        if (onMediaOffer?.invoke(offer, packet.senderId) != true) return
        synchronized(incoming) {
            incoming[offer.hashHex] = IncomingTransfer(offer) to packet.senderId
        }
        send(PacketType.MEDIA_REQUEST, MediaCodecs.encodeRequest(offer.blobHash), recipientId = packet.senderId)
    }

    private fun handleMediaRequest(packet: Packet) {
        val store = blobStore ?: return
        val hash = try {
            MediaCodecs.decodeRequest(packet.payload)
        } catch (_: ProtocolException) {
            return
        }
        val hashHex = hash.joinToString("") { "%02x".format(it) }
        if (!store.has(hashHex)) return
        val data = store.read(hashHex)
        val chunkSize = MediaCodecs.DEFAULT_CHUNK_SIZE
        var index = 0
        var offset = 0
        while (offset < data.size || (data.isEmpty() && index == 0)) {
            val end = minOf(offset + chunkSize, data.size)
            send(
                PacketType.MEDIA_CHUNK,
                MediaCodecs.encodeChunk(hash, index, data.copyOfRange(offset, end)),
                recipientId = packet.senderId,
            )
            index++
            offset = end
        }
    }

    private fun handleMediaChunk(packet: Packet) {
        val store = blobStore ?: return
        val chunk = try {
            MediaCodecs.decodeChunk(packet.payload)
        } catch (_: ProtocolException) {
            return
        }
        val hashHex = chunk.blobHash.joinToString("") { "%02x".format(it) }
        val (transfer, from) = synchronized(incoming) { incoming[hashHex] } ?: return
        transfer.accept(chunk.index, chunk.data)
        if (!transfer.isComplete()) return
        val data = transfer.assembleVerified()
        synchronized(incoming) { incoming.remove(hashHex) }
        if (data == null) return // hash mismatch: forged or corrupted transfer, drop it all
        store.put(data)
        onMediaReceived?.invoke(hashHex, transfer.offer.mimeTag, from)
    }

    private fun transmit(packet: Packet) {
        onTransmit?.invoke(packet)
        transport.broadcast(PacketCodec.encode(packet))
    }
}
