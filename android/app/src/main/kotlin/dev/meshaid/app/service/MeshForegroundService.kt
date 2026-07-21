package dev.meshaid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import dev.meshaid.app.MeshRepository
import dev.meshaid.app.ble.BleMeshTransport
import dev.meshaid.core.MeshNode
import dev.meshaid.core.crypto.Identity
import dev.meshaid.core.protocol.GpsBeacon
import dev.meshaid.core.protocol.Packet
import dev.meshaid.core.protocol.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Always-on mesh relay: typed connectedDevice foreground service that keeps the BLE
 * transport, router, and dedup cache alive while the app is backgrounded.
 */
class MeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "mesh"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: MeshForegroundService? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeshForegroundService::class.java))
        }
    }

    private lateinit var identity: Identity
    private lateinit var transport: BleMeshTransport
    private lateinit var node: MeshNode
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        identity = IdentityStore.loadOrCreate(this)
        transport = BleMeshTransport(this, identity.nodeId)
        node = MeshNode(identity.nodeId, System::currentTimeMillis, transport)
        node.onMessage = ::onPacket

        startForegroundWithType()
        node.start()
        MeshRepository.setMeshRunning(true)

        scope.launch {
            while (true) {
                MeshRepository.setPeerCount(transport.linkCount())
                delay(2000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        MeshRepository.setMeshRunning(false)
        scope.cancel()
        node.stop()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun selfIdHex(): String = identity.nodeId.toString()

    fun sendChat(text: String) {
        val packet = node.send(PacketType.CHAT, text.toByteArray(), sign = identity::sign)
        MeshRepository.addMessage(
            MeshRepository.ChatMessage(
                fromId = identity.nodeId.toString(),
                text = text,
                timestampMs = packet.timestampMs,
                mine = true,
            ),
        )
    }

    fun sendSos(note: String) {
        val beacon = bestEffortBeacon()
        val payload = beacon.encode() + note.toByteArray()
        val packet = node.send(PacketType.SOS, payload, sign = identity::sign)
        MeshRepository.addMessage(
            MeshRepository.ChatMessage(
                fromId = identity.nodeId.toString(),
                text = "SOS: $note (${formatFix(beacon)})",
                timestampMs = packet.timestampMs,
                mine = true,
                isSos = true,
            ),
        )
    }

    private fun onPacket(packet: Packet) {
        when (packet.type) {
            PacketType.CHAT -> MeshRepository.addMessage(
                MeshRepository.ChatMessage(
                    fromId = packet.senderId.toString(),
                    text = String(packet.payload),
                    timestampMs = packet.timestampMs,
                    mine = false,
                ),
            )
            PacketType.SOS -> {
                val beacon = runCatching { GpsBeacon.decode(packet.payload) }.getOrNull()
                val note = if (packet.payload.size > GpsBeacon.SIZE) {
                    String(packet.payload, GpsBeacon.SIZE, packet.payload.size - GpsBeacon.SIZE)
                } else ""
                MeshRepository.addMessage(
                    MeshRepository.ChatMessage(
                        fromId = packet.senderId.toString(),
                        text = "SOS: $note (${beacon?.let(::formatFix) ?: "no fix"})",
                        timestampMs = packet.timestampMs,
                        mine = false,
                        isSos = true,
                    ),
                )
            }
            else -> Unit // GPS beacons, presence, DTN sync: wired to the map/store in Phase 1
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
            .setContentTitle("MeshAid mesh active")
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
