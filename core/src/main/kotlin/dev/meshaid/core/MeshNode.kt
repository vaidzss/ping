package dev.meshaid.core

import dev.meshaid.core.blob.BlobStore
import dev.meshaid.core.crypto.Identity
import dev.meshaid.core.crypto.PeerDirectory
import dev.meshaid.core.crypto.SealedBox
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
import dev.meshaid.core.protocol.PresencePayload
import dev.meshaid.core.protocol.ProtocolException
import dev.meshaid.core.protocol.SummaryVector

/**
 * A delivered application message: raw packet plus the decrypted payload and the
 * signature verdict (`true` verified, `false` unsigned-or-unknown-key, never a forgery —
 * packets with an invalid signature from a known key are dropped before delivery).
 */
class MeshMessage(
    val packet: Packet,
    val payload: ByteArray,
    val verified: Boolean,
    /** True when this arrived as an encrypted direct message addressed to us. */
    val direct: Boolean,
)

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
    private val identity: Identity? = null,
) {
    companion object {
        const val PRESENCE_TIMEOUT_MS = 35_000L
        const val SYNC_INTERVAL_MS = 30_000L
        const val MAX_AUTO_FETCH_BYTES = 1 shl 20 // control-lane media cap: 1 MiB
    }

    val router = MeshRouter(selfId, clock, config)

    /** Peers' announced keys+names, learned from presence (id/key binding verified). */
    val directory = PeerDirectory()

    /** Delivered application messages (CHAT / SOS / GPS_BEACON). */
    var onMessage: ((MeshMessage) -> Unit)? = null

    /** Diagnostics: every frame this node puts on the air (send + relay). */
    var onTransmit: ((Packet) -> Unit)? = null

    /** A direct neighbor announced itself (nodeId, displayName). */
    var onPeerPresence: ((NodeId, String) -> Unit)? = null

    /**
     * A new transport link came up. The app/CLI should answer with an immediate presence
     * so keys are exchanged at once — otherwise a DM typed in the first few seconds fails
     * because the recipient's keys haven't arrived on the next heartbeat yet.
     */
    var onNeighborUp: ((NodeId) -> Unit)? = null

    /** A media offer arrived. Return true to fetch the blob. */
    var onMediaOffer: ((MediaOffer, NodeId) -> Boolean)? = null

    /** A fetched blob passed hash verification and is now in the blob store. */
    var onMediaReceived: ((hashHex: String, mimeTag: Int, from: NodeId) -> Unit)? = null

    private val presenceSeen = HashMap<NodeId, Long>()
    private val lastSync = HashMap<NodeId, Long>()
    private val incoming = HashMap<String, Pair<IncomingTransfer, NodeId>>()

    init {
        transport.onFrame = ::receiveFrame
        transport.onPeerConnected = {
            peerUp(it)
            onNeighborUp?.invoke(it)
        }
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
        val signer = sign ?: identity?.takeIf { type == PacketType.CHAT || type == PacketType.SOS }?.let { it::sign }
        signer?.let { packet = packet.withSignature(it(PacketCodec.signingBytes(packet))) }
        storeAsBundle(packet)
        transmit(packet)
        return packet
    }

    /** Announce ourselves to direct neighbors only (TTL 1 — never relayed). */
    fun sendPresence(displayName: String) {
        val payload = PresencePayload(
            name = displayName,
            signingPublic = identity?.signingPublic?.encoded,
            dhPublic = identity?.dhPublic?.encoded,
        ).encode()
        send(PacketType.PRESENCE, payload, ttl = 1)
    }

    /**
     * Multi-hop identity announcement (same payload as presence, full TTL): lets distant
     * nodes learn our keys/name so DMs can route across relays. Receivers do NOT treat
     * announce senders as direct neighbors (except the unavoidable ttl-1 last hop, which
     * presence expiry self-heals in 35 s).
     */
    fun sendAnnounce(displayName: String) {
        val payload = PresencePayload(
            name = displayName,
            signingPublic = identity?.signingPublic?.encoded,
            dhPublic = identity?.dhPublic?.encoded,
        ).encode()
        send(PacketType.PRESENCE, payload)
    }

    /**
     * Encrypted 1:1 message, sealed to the recipient's announced X25519 key.
     * Requires our identity and the recipient's presence to have been seen.
     * Sealed-box today (no forward secrecy — the Noise X trade-off for offline
     * delivery); interactive Noise XX sessions are the planned upgrade.
     */
    fun sendDirectChat(recipientId: NodeId, text: String): Packet {
        checkNotNull(identity) { "MeshNode has no identity — cannot send DMs" }
        val keys = directory.get(recipientId)
            ?: error("no announced keys for $recipientId — wait for their presence")
        val sealed = SealedBox.seal(keys.dhPublic, text.toByteArray())
        return send(
            PacketType.CHAT,
            sealed,
            recipientId = recipientId,
            sign = identity::sign,
            encrypted = true,
        )
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
                val presence = try {
                    PresencePayload.decode(packet.payload)
                } catch (_: ProtocolException) {
                    return
                }
                // ttl 1 = direct presence (never relayed); higher ttl = multi-hop announce.
                if (packet.ttl == 1) peerUp(packet.senderId)
                if (presence.hasKeys) directory.register(packet.senderId, presence)
                onPeerPresence?.invoke(packet.senderId, presence.name)
            }
            PacketType.SUMMARY_VECTOR -> handleSummaryVector(packet)
            PacketType.BUNDLE_PULL -> handleBundlePull(packet)
            PacketType.MEDIA_OFFER -> handleMediaOffer(packet)
            PacketType.MEDIA_REQUEST -> handleMediaRequest(packet)
            PacketType.MEDIA_CHUNK -> handleMediaChunk(packet)
            else -> {
                storeAsBundle(packet)
                deliver(packet)
            }
        }
    }

    /** Verify, decrypt, and hand a CHAT/SOS/GPS packet to the app layer. */
    private fun deliver(packet: Packet) {
        var verified = false
        val signature = packet.signature
        if (signature != null) {
            val keys = directory.get(packet.senderId)
            if (keys != null) {
                if (!Identity.verify(keys.signingPublic, PacketCodec.signingBytes(packet), signature)) {
                    return // invalid signature from a known key: forged packet, drop it
                }
                verified = true
            }
        }
        var payload = packet.payload
        var direct = false
        if (packet.encrypted) {
            if (packet.recipientId != selfId) return // sealed for someone else; nothing to show
            val self = identity ?: return
            payload = try {
                SealedBox.open(self, packet.payload)
            } catch (_: Exception) {
                return // not openable by us: damaged or misaddressed
            }
            direct = true
        }
        onMessage?.invoke(MeshMessage(packet, payload, verified, direct))
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
