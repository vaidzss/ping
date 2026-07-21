package dev.meshaid.core.mesh

import dev.meshaid.core.protocol.NodeId
import dev.meshaid.core.protocol.Packet
import dev.meshaid.core.protocol.PacketCodec
import dev.meshaid.core.protocol.PacketType

data class MeshConfig(
    val baseTtl: Int = 7,
    /** With this many direct neighbors or more, relayed TTL is clamped (dense-crowd defense). */
    val denseNeighborThreshold: Int = 6,
    val denseTtlClamp: Int = 5,
    /** Per-origin relay budget (SOS exempt). */
    val relayTokensPerSecond: Double = 5.0,
    val relayBurst: Int = 10,
    /** Control-lane payload cap; larger packets belong on the bulk lane. */
    val maxControlPayload: Int = 4096,
)

data class RouteResult(
    /** Hand the packet to the local app layer. */
    val deliver: Boolean,
    /** Rebroadcast this (TTL-decremented, possibly clamped) packet, or null. */
    val relay: Packet?,
    /** Why the packet was dropped or not relayed (diagnostics). */
    val reason: String? = null,
)

/**
 * TTL flooding with dedup, density-aware TTL clamping, and per-origin rate limiting.
 * Pure logic: no radios, no threads — drive it from any transport.
 */
class MeshRouter(
    val selfId: NodeId,
    private val clock: () -> Long,
    private val config: MeshConfig = MeshConfig(),
    private val dedup: DedupCache = DedupCache(clock),
) {
    private val neighbors = LinkedHashSet<NodeId>()
    private val originBuckets = HashMap<NodeId, TokenBucket>()

    @Synchronized
    fun neighborUp(id: NodeId) {
        if (id != selfId) neighbors.add(id)
    }

    @Synchronized
    fun neighborDown(id: NodeId) {
        neighbors.remove(id)
    }

    @Synchronized
    fun neighborCount(): Int = neighbors.size

    /** Build an outbound packet from this node with density-appropriate TTL. */
    @Synchronized
    fun prepareOutbound(
        type: PacketType,
        payload: ByteArray,
        recipientId: NodeId? = null,
        encrypted: Boolean = false,
    ): Packet {
        val packet = Packet(
            type = type,
            ttl = clampedTtl(config.baseTtl),
            timestampMs = clock(),
            senderId = selfId,
            recipientId = recipientId,
            payload = payload,
            encrypted = encrypted,
        )
        // Record our own message so an echoed relay of it is not re-delivered to us.
        dedup.checkAndRecord(PacketCodec.messageId(packet))
        return packet
    }

    @Synchronized
    fun onReceive(packet: Packet): RouteResult {
        if (packet.senderId == selfId) return RouteResult(deliver = false, relay = null, reason = "own packet")
        if (packet.payload.size > config.maxControlPayload && packet.type != PacketType.MEDIA_CHUNK) {
            return RouteResult(deliver = false, relay = null, reason = "oversized control payload")
        }
        if (!dedup.checkAndRecord(PacketCodec.messageId(packet))) {
            return RouteResult(deliver = false, relay = null, reason = "duplicate")
        }

        val forUs = packet.recipientId == null || packet.recipientId == selfId
        val terminal = packet.recipientId == selfId

        val relay: Packet?
        var reason: String? = null
        if (terminal) {
            relay = null
        } else {
            val newTtl = clampedTtl(packet.ttl - 1)
            if (newTtl <= 0) {
                relay = null
                reason = "ttl expired"
            } else if (packet.type != PacketType.SOS && !bucketFor(packet.senderId).tryConsume(clock())) {
                relay = null
                reason = "relay rate limited for origin ${packet.senderId}"
            } else {
                relay = packet.withTtl(newTtl)
            }
        }
        return RouteResult(deliver = forUs, relay = relay, reason = reason)
    }

    private fun clampedTtl(ttl: Int): Int {
        val capped = ttl.coerceAtMost(PacketCodec.MAX_TTL)
        return if (neighbors.size >= config.denseNeighborThreshold) {
            capped.coerceAtMost(config.denseTtlClamp)
        } else capped
    }

    private fun bucketFor(origin: NodeId): TokenBucket =
        originBuckets.getOrPut(origin) { TokenBucket(config.relayBurst, config.relayTokensPerSecond) }
}

internal class TokenBucket(
    private val capacity: Int,
    private val refillPerSecond: Double,
) {
    private var tokens: Double = capacity.toDouble()
    private var lastRefillMs: Long = Long.MIN_VALUE

    fun tryConsume(nowMs: Long): Boolean {
        if (lastRefillMs == Long.MIN_VALUE) lastRefillMs = nowMs
        val elapsed = (nowMs - lastRefillMs).coerceAtLeast(0)
        tokens = (tokens + elapsed / 1000.0 * refillPerSecond).coerceAtMost(capacity.toDouble())
        lastRefillMs = nowMs
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}
