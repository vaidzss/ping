package dev.meshaid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import dev.meshaid.app.MeshRepository
import dev.meshaid.app.ble.BleMeshTransport
import dev.meshaid.app.media.VideoTranscoder
import dev.meshaid.core.MeshMessage
import dev.meshaid.core.MeshNode
import dev.meshaid.core.blob.BlobStore
import dev.meshaid.core.crypto.ContactCard
import dev.meshaid.core.crypto.Identity
import dev.meshaid.core.crypto.StorageVault
import dev.meshaid.core.dtn.BundleStore
import dev.meshaid.core.media.MimeTag
import dev.meshaid.core.protocol.GpsBeacon
import dev.meshaid.core.protocol.NodeId
import dev.meshaid.core.protocol.Packet
import dev.meshaid.core.protocol.PacketType
import dev.meshaid.core.transport.CompositeMeshTransport
import dev.meshaid.core.transport.LanMeshTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Always-on mesh relay: typed connectedDevice foreground service that keeps the BLE
 * transport, router, DTN store, and blob store alive while the app is backgrounded.
 */
class MeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "mesh"
        private const val NOTIFICATION_ID = 1
        private const val PRESENCE_INTERVAL_MS = 10_000L
        private const val BEACON_INTERVAL_MS = 30_000L
        private const val MAX_IMAGE_DIMENSION = 1280
        private const val TARGET_IMAGE_BYTES = 600 * 1024

        @Volatile
        var instance: MeshForegroundService? = null
            private set

        // Handed off in-process from the just-unlocked login/signup screen, never through
        // an Intent extra — the private key material never needs to leave the process.
        @Volatile
        private var pendingIdentity: Identity? = null

        fun start(context: Context, identity: Identity) {
            pendingIdentity = identity
            context.startForegroundService(Intent(context, MeshForegroundService::class.java))
        }
    }

    private lateinit var identity: Identity
    private lateinit var bleLane: BleMeshTransport
    private lateinit var lanLane: LanMeshTransport
    private lateinit var node: MeshNode
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var bundleStore: BundleStore
    private lateinit var messageLog: MessageLog
    lateinit var blobStore: BlobStore
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun record(message: MeshRepository.ChatMessage) {
        messageLog.append(message)
        MeshRepository.addMessage(message)
    }

    override fun onCreate() {
        super.onCreate()
        val unlocked = pendingIdentity
        if (unlocked == null) {
            // Can happen if Android restarts the service after the process was killed —
            // there's no unlocked key to run with until the user logs in again.
            stopSelf()
            return
        }
        pendingIdentity = null
        identity = unlocked
        instance = this
        bleLane = BleMeshTransport(this, identity.nodeId)
        lanLane = LanMeshTransport(identity.nodeId)
        lanLane.onDiagnostic = { message ->
            MeshRepository.addMessage(
                MeshRepository.ChatMessage(
                    fromId = "system",
                    text = "LAN: $message",
                    timestampMs = System.currentTimeMillis(),
                    mine = false,
                    system = true,
                ),
            )
        }
        // Android filters multicast by default; without this lock LAN discovery is deaf.
        multicastLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createMulticastLock("meshaid").apply {
                setReferenceCounted(false)
                acquire()
            }
        // One key, derived once from the unlocked identity (not the password — see
        // StorageVault's doc comment), used to encrypt everything this device persists locally.
        val storageKey = StorageVault.deriveKey(identity)
        blobStore = BlobStore(
            filesDir.resolve("blobs").toPath(),
            seal = { StorageVault.seal(it, storageKey) },
            open = { StorageVault.open(it, storageKey) },
        )
        messageLog = MessageLog(filesDir.resolve("messages.jsonl"), storageKey)
        MeshRepository.seedHistory(messageLog.load())
        MeshRepository.setSelfCallsign(displayName())
        MeshRepository.setFriends(FriendStore.load(this))
        val bundles = BundleStore(System::currentTimeMillis)
        bundleStore = bundles
        node = MeshNode(
            selfId = identity.nodeId,
            clock = System::currentTimeMillis,
            transport = CompositeMeshTransport(listOf(bleLane, lanLane)),
            bundleStore = bundles,
            blobStore = blobStore,
            identity = identity,
        )
        node.onMessage = ::onPacket
        node.onPeerPresence = { peer, name ->
            val known = node.directory.get(peer) != null
            MeshRepository.updatePeer(peer.toString()) {
                it.copy(name = name, lastSeenMs = System.currentTimeMillis(), verified = known)
            }
        }
        node.onNeighborUp = {
            // Answer a new link instantly so keys are shared before the user can DM.
            node.sendPresence(displayName())
            node.sendAnnounce(displayName())
        }
        node.onDeliveryDropped = { packet, reason ->
            MeshRepository.addMessage(
                MeshRepository.ChatMessage(
                    fromId = "system",
                    text = "DROPPED ${packet.type} from ${MeshRepository.displayName(packet.senderId.toString())}: $reason",
                    timestampMs = System.currentTimeMillis(),
                    mine = false,
                    system = true,
                ),
            )
        }
        node.onFrameRejected = { size, reason ->
            MeshRepository.addMessage(
                MeshRepository.ChatMessage(
                    fromId = "system",
                    text = "REJECTED incoming frame ($size bytes): $reason",
                    timestampMs = System.currentTimeMillis(),
                    mine = false,
                    system = true,
                ),
            )
        }
        node.onMediaOffer = { offer, _ -> offer.totalSize <= MeshNode.MAX_AUTO_FETCH_BYTES }
        node.onMediaReceived = { hashHex, mimeTag, from ->
            when (mimeTag) {
                MimeTag.JPEG, MimeTag.PNG -> record(
                    MeshRepository.ChatMessage(
                        fromId = from.toString(),
                        text = "",
                        timestampMs = System.currentTimeMillis(),
                        mine = false,
                        imageHash = hashHex,
                    ),
                )
                MimeTag.MP4 -> record(
                    MeshRepository.ChatMessage(
                        fromId = from.toString(),
                        text = "",
                        timestampMs = System.currentTimeMillis(),
                        mine = false,
                        videoHash = hashHex,
                    ),
                )
                else -> Unit
            }
        }

        startForegroundWithType()
        node.start()
        MeshRepository.setMeshRunning(true)

        scope.launch {
            while (true) {
                node.tick()
                val links = bleLane.linkCount() + lanLane.peerCount()
                MeshRepository.setPeerCount(links.coerceAtLeast(node.router.neighborCount()))
                MeshRepository.setCarryingCount(bundleStore.size())
                delay(2000)
            }
        }
        scope.launch {
            var beat = 0
            while (true) {
                node.sendPresence(displayName())
                // Every third beat, announce identity mesh-wide so distant peers can DM us.
                if (beat++ % 3 == 0) node.sendAnnounce(displayName())
                delay(PRESENCE_INTERVAL_MS)
            }
        }
        scope.launch {
            while (true) {
                delay(BEACON_INTERVAL_MS)
                bestEffortBeacon().takeIf { it.latE7 != 0 || it.lonE7 != 0 }?.let {
                    MeshRepository.setSelfLocation(it.lat, it.lon)
                    node.send(PacketType.GPS_BEACON, it.encode())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        MeshRepository.setMeshRunning(false)
        scope.cancel()
        node.stop()
        runCatching { multicastLock?.release() }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun displayName(): String =
        IdentityStore.username(this) ?: "Ping-${identity.nodeId.toString().take(6)}"

    /**
     * Broadcast chat only — DMs go through [sendDirectMessage] via a friend's own thread now,
     * so there's no "@name" command left to parse here. Called directly from a Compose
     * onClick (main thread); `node.send()` ends up doing a blocking transport socket write
     * (`LanMeshTransport.PeerLink.sendFrame`), which Android forbids on the main thread — it
     * used to throw `NetworkOnMainThreadException` right there, silently killing the LAN link
     * on *every* chat/DM/SOS send while photos (already routed through `scope.launch` in
     * `sendImage`) worked fine. Dispatch here, not at each call site, so nothing can regress
     * this by forgetting to.
     */
    fun sendChat(text: String) = scope.launch {
        val packet = node.send(PacketType.CHAT, text.toByteArray())
        record(
            MeshRepository.ChatMessage(
                fromId = identity.nodeId.toString(),
                text = text,
                timestampMs = packet.timestampMs,
                mine = true,
            ),
        )
    }

    /** Encrypted 1:1 message to a specific friend — the composer inside their thread screen. */
    fun sendDirectMessage(peerId: NodeId, body: String) = scope.launch {
        val packet = node.sendDirectChat(peerId, body)
        record(
            MeshRepository.ChatMessage(
                fromId = identity.nodeId.toString(),
                text = body,
                timestampMs = packet.timestampMs,
                mine = true,
                direct = true,
                peerId = peerId.toString(),
            ),
        )
    }

    /** Adds a nearby peer as a friend — the only thing that makes them appear in the Roster. */
    fun addFriend(id: String, name: String) {
        FriendStore.add(this, id, name)
        MeshRepository.setFriends(FriendStore.load(this))
    }

    /** The QR payload for someone to scan in person to add this device as a verified contact. */
    fun myContactCard(): ContactCard = ContactCard.of(identity, displayName())

    /**
     * Registers a scanned contact card: stronger trust than the ordinary "seen broadcasting
     * nearby" add, since the keys came from an in-person QR scan rather than an unauthenticated
     * presence packet anyone in range could send. Works even before the peer is ever heard on
     * the mesh — DMs to them can be sent as soon as they're in range, no waiting for presence.
     */
    fun addVerifiedContact(card: ContactCard): String {
        val nodeId = node.directory.registerVerified(card.name, card.signingPublic, card.dhPublic)
        val id = nodeId.toString()
        FriendStore.add(this, id, card.name)
        MeshRepository.setFriends(FriendStore.load(this))
        MeshRepository.updatePeer(id) { it.copy(name = card.name, verified = true) }
        return id
    }

    fun sendSos(note: String) = scope.launch {
        val beacon = bestEffortBeacon()
        val payload = beacon.encode() + note.toByteArray()
        val packet = node.send(PacketType.SOS, payload)
        record(
            MeshRepository.ChatMessage(
                fromId = identity.nodeId.toString(),
                text = "SOS: $note (${formatFix(beacon)})",
                timestampMs = packet.timestampMs,
                mine = true,
                isSos = true,
            ),
        )
    }

    /** Downscale + recompress a picked image and offer it to the mesh. */
    fun sendImage(uri: Uri) {
        scope.launch {
            val jpeg = runCatching { compressForMesh(uri) }.getOrNull() ?: return@launch
            val hash = node.offerMedia(jpeg, MimeTag.JPEG)
            record(
                MeshRepository.ChatMessage(
                    fromId = identity.nodeId.toString(),
                    text = "",
                    timestampMs = System.currentTimeMillis(),
                    mine = true,
                    imageHash = hash,
                ),
            )
        }
    }

    fun sendVideo(uri: Uri) {
        scope.launch {
            // HEVC transcode can take a real stretch of wall-clock time even when it's going
            // to succeed — with no feedback at all until it finishes, "still working" and
            // "silently stuck" look identical to whoever's waiting on it.
            MeshRepository.addMessage(
                MeshRepository.ChatMessage(
                    fromId = "system",
                    text = "Preparing video for the mesh — this can take a moment…",
                    timestampMs = System.currentTimeMillis(),
                    mine = false,
                    system = true,
                ),
            )
            val mp4 = runCatching { VideoTranscoder.transcode(this@MeshForegroundService, uri, cacheDir) }
                .onFailure { e ->
                    MeshRepository.addMessage(
                        MeshRepository.ChatMessage(
                            fromId = "system",
                            text = "Couldn't prepare that video for the mesh: ${e.message}",
                            timestampMs = System.currentTimeMillis(),
                            mine = false,
                            system = true,
                        ),
                    )
                }
                .getOrNull() ?: return@launch
            if (mp4.size > MeshNode.MAX_AUTO_FETCH_BYTES) {
                MeshRepository.addMessage(
                    MeshRepository.ChatMessage(
                        fromId = "system",
                        text = "That clip is still too big for the mesh even after compression — try a shorter one.",
                        timestampMs = System.currentTimeMillis(),
                        mine = false,
                        system = true,
                    ),
                )
                return@launch
            }
            val hash = node.offerMedia(mp4, MimeTag.MP4)
            record(
                MeshRepository.ChatMessage(
                    fromId = identity.nodeId.toString(),
                    text = "",
                    timestampMs = System.currentTimeMillis(),
                    mine = true,
                    videoHash = hash,
                ),
            )
        }
    }

    private fun compressForMesh(uri: Uri): ByteArray {
        val source = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("cannot read $uri")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_DIMENSION || bounds.outHeight / sample > MAX_IMAGE_DIMENSION) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            source, 0, source.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("cannot decode image")
        var quality = 80
        var out: ByteArray
        do {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            out = stream.toByteArray()
            quality -= 15
        } while (out.size > TARGET_IMAGE_BYTES && quality >= 20)
        bitmap.recycle()
        check(out.size <= MeshNode.MAX_AUTO_FETCH_BYTES) { "image still too large for the control lane" }
        return out
    }

    private fun onPacket(message: MeshMessage) {
        val packet = message.packet
        val payload = message.payload
        when (packet.type) {
            PacketType.CHAT -> record(
                MeshRepository.ChatMessage(
                    fromId = packet.senderId.toString(),
                    text = String(payload),
                    timestampMs = packet.timestampMs,
                    mine = false,
                    verified = message.verified,
                    direct = message.direct,
                    peerId = if (message.direct) packet.senderId.toString() else null,
                ),
            )
            PacketType.SOS -> {
                val beacon = runCatching { GpsBeacon.decode(payload) }.getOrNull()
                val note = if (payload.size > GpsBeacon.SIZE) {
                    String(payload, GpsBeacon.SIZE, payload.size - GpsBeacon.SIZE)
                } else ""
                beacon?.takeIf { it.latE7 != 0 || it.lonE7 != 0 }?.let { fix ->
                    MeshRepository.updatePeer(packet.senderId.toString()) {
                        it.copy(lat = fix.lat, lon = fix.lon, lastSeenMs = System.currentTimeMillis())
                    }
                }
                record(
                    MeshRepository.ChatMessage(
                        fromId = packet.senderId.toString(),
                        text = "SOS: $note (${beacon?.let(::formatFix) ?: "no fix"})",
                        timestampMs = packet.timestampMs,
                        mine = false,
                        isSos = true,
                        verified = message.verified,
                    ),
                )
            }
            PacketType.GPS_BEACON -> {
                runCatching { GpsBeacon.decode(payload) }.getOrNull()?.let { fix ->
                    MeshRepository.updatePeer(packet.senderId.toString()) {
                        it.copy(lat = fix.lat, lon = fix.lon, lastSeenMs = System.currentTimeMillis())
                    }
                }
            }
            else -> Unit
        }
    }

    private fun formatFix(beacon: GpsBeacon): String =
        if (beacon.latE7 == 0 && beacon.lonE7 == 0) "no fix"
        else "%.5f, %.5f".format(beacon.lat, beacon.lon)

    private fun bestEffortBeacon(): GpsBeacon {
        val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val location = runCatching {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.getProviders(true).asSequence()
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull()
        return if (location != null) {
            GpsBeacon.of(location.latitude, location.longitude, location.accuracy.toInt(), battery)
        } else {
            GpsBeacon(0, 0, 0, battery)
        }
    }

    private fun startForegroundWithType() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mesh relay", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Ping mesh active")
            .setContentText("Relaying messages for people nearby — no internet needed")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
