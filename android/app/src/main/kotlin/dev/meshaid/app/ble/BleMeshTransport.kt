package dev.meshaid.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import dev.meshaid.core.protocol.NodeId
import dev.meshaid.core.transport.MeshTransport
import java.util.UUID

/**
 * BLE control-lane transport: advertise our presence, scan for peers, exchange frames over
 * GATT. To avoid duplicate pair connections, only the node with the numerically smaller id
 * initiates the GATT client connection; the other side answers via server notifications.
 *
 * Phase-0 known limits (by design, see plan): peer NodeId on server-side links is unknown
 * until a first-frame handshake lands (density undercount is safe), and writes assume the
 * default queue behavior of the platform stack.
 */
@SuppressLint("MissingPermission")
class BleMeshTransport(
    private val context: Context,
    private val selfId: NodeId,
) : MeshTransport {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("4d455348-4149-4400-a1d0-000000000001")
        val FRAME_WRITE_UUID: UUID = UUID.fromString("4d455348-4149-4400-a1d0-000000000002")
        val FRAME_NOTIFY_UUID: UUID = UUID.fromString("4d455348-4149-4400-a1d0-000000000003")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val REQUESTED_MTU = 517
        private const val DEFAULT_WRITE = 20 // MTU 23 - 3, until negotiated

        // Presence broadcasts every 10s (MeshForegroundService.PRESENCE_INTERVAL_MS) flow
        // through every connected link, so a healthy connection should never go this quiet.
        // 2.5x that interval, with slack for scheduling jitter.
        private const val STALE_LINK_TIMEOUT_MS = 25_000L

        // A single write/notification normally completes in well under a second. This is
        // deliberately much shorter than STALE_LINK_TIMEOUT_MS — it's not "is the link dead",
        // it's "did this one in-flight send's completion callback go missing", which a
        // multi-hundred-chunk transfer is exactly the kind of thing to trigger.
        private const val SEND_STUCK_TIMEOUT_MS = 6_000L
    }

    override var onFrame: ((ByteArray) -> Unit)? = null
    override var onPeerConnected: ((NodeId) -> Unit)? = null
    override var onPeerDisconnected: ((NodeId) -> Unit)? = null

    /**
     * BLE going quiet used to be a pure logcat warning (`Log.w`) — invisible outside a
     * debugger. `bluetoothLeAdvertiser`/`bluetoothLeScanner` both return null with zero other
     * signal when the radio itself is off, which is exactly the case a real user hits.
     */
    var onDiagnostic: ((String) -> Unit)? = null

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var started = false
    private var reportedDisabled = false

    // notifyCharacteristicChanged() queues internally and only allows one outstanding
    // notification at a time — calling it again before onNotificationSent() fires for the
    // previous one silently drops data rather than erroring. A multi-hundred-chunk photo
    // transfer hit this on every attempt; a one-chunk chat message got lucky often enough
    // to look like it worked. Paced the same way the client-write side already was.
    //
    // `notifying` is shared across every connected peer, not per-link — if onNotificationSent
    // never fires for one in-flight notification (the peer disconnects mid-send, a stale link
    // gets pruned while a notification to it is outstanding, or Android's stack just drops the
    // callback — all real, all seen), this flag gets stuck true forever and silently freezes
    // ALL future BLE sends to EVERY peer, not just the one that stalled. A multi-hundred-chunk
    // photo/video transfer is exactly what's likely to hit this. notifyingSince lets a
    // watchdog force it back open.
    private val notifyQueue = ArrayDeque<Pair<BluetoothDevice, ByteArray>>()
    private var notifying = false
    private var notifyingSince = 0L

    private inner class ClientLink(val peerId: NodeId, val device: BluetoothDevice) {
        var gatt: BluetoothGatt? = null
        var writeCharacteristic: BluetoothGattCharacteristic? = null
        var maxWrite = DEFAULT_WRITE
        var ready = false
        var writing = false
        var writingSince = 0L
        var lastActivityMs = System.currentTimeMillis()
        val queue = ArrayDeque<ByteArray>()
        val reassembler = BleFraming.Reassembler()
    }

    private inner class ServerLink(val device: BluetoothDevice) {
        var subscribed = false
        var maxWrite = DEFAULT_WRITE
        var lastActivityMs = System.currentTimeMillis()
        val reassembler = BleFraming.Reassembler()
    }

    private val clientLinks = HashMap<String, ClientLink>() // by device address
    private val serverLinks = HashMap<String, ServerLink>()

    @Synchronized
    fun linkCount(): Int = clientLinks.values.count { it.ready } + serverLinks.values.count { it.subscribed }

    /**
     * Android's `onConnectionStateChange` doesn't reliably fire for every real-world failure
     * mode — a link can go "zombie": Android still reports it connected while the radio-level
     * link is actually dead (a known issue on combo Wi-Fi/BT chipsets, where toggling Wi-Fi can
     * glitch the BT radio via shared coexistence hardware). Presence flows through every link
     * every ~10s, so silence past [STALE_LINK_TIMEOUT_MS] means the link is lying — force it
     * closed so the scan/advertise loop can rebuild it fresh, rather than sitting on a link
     * that looks connected but will never deliver anything again.
     */
    @Synchronized
    fun pruneStaleLinks() {
        if (!started) return
        val now = System.currentTimeMillis()

        val staleClients = clientLinks.filterValues { it.ready && now - it.lastActivityMs > STALE_LINK_TIMEOUT_MS }
        staleClients.forEach { (address, link) ->
            onDiagnostic?.invoke("BLE link to ${link.peerId} went quiet — reconnecting")
            clientLinks.remove(address)
            runCatching { link.gatt?.disconnect() }
            runCatching { link.gatt?.close() }
            onPeerDisconnected?.invoke(link.peerId)
        }

        val staleServers = serverLinks.filterValues { it.subscribed && now - it.lastActivityMs > STALE_LINK_TIMEOUT_MS }
        staleServers.forEach { (address, link) ->
            onDiagnostic?.invoke("BLE link from a subscriber went quiet — dropping it")
            serverLinks.remove(address)
            runCatching { gattServer?.cancelConnection(link.device) }
        }

        // notifying/writing are only ever meant to be true for as long as one send is
        // in flight. If a completion callback goes missing, these get stuck true forever and
        // silently freeze every future send through that path — this is the recovery for that.
        if (notifying && now - notifyingSince > SEND_STUCK_TIMEOUT_MS) {
            onDiagnostic?.invoke("BLE notification queue stuck — resuming it")
            notifying = false
            pumpNotifyQueue()
        }
        clientLinks.values.forEach { link ->
            if (link.writing && now - link.writingSince > SEND_STUCK_TIMEOUT_MS) {
                onDiagnostic?.invoke("BLE write to ${link.peerId} stuck — resuming it")
                link.writing = false
                pumpQueue(link)
            }
        }
    }

    /**
     * Safe to call repeatedly — a no-op once actually running, and a cheap retry otherwise.
     * The caller polls this rather than calling it once, so the mesh comes up on its own the
     * moment the user flips Bluetooth on, with no separate ACTION_STATE_CHANGED receiver needed.
     */
    @Synchronized
    override fun start() {
        if (started) return
        if (manager.adapter?.isEnabled != true) {
            if (!reportedDisabled) {
                onDiagnostic?.invoke("Bluetooth is off — the mesh can't reach anyone until it's turned on")
                reportedDisabled = true
            }
            return
        }
        reportedDisabled = false
        started = true
        startServer()
        startAdvertising()
        startScanning()
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        started = false
        runCatching { manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        clientLinks.values.forEach { runCatching { it.gatt?.close() } }
        clientLinks.clear()
        serverLinks.clear()
        runCatching { gattServer?.close() }
        gattServer = null
        notifyQueue.clear()
        notifying = false
    }

    @Synchronized
    override fun broadcast(frame: ByteArray) {
        for (link in clientLinks.values) {
            if (!link.ready) continue
            BleFraming.fragment(frame, link.maxWrite).forEach { link.queue.add(it) }
            pumpQueue(link)
        }
        for (link in serverLinks.values) {
            if (!link.subscribed) continue
            BleFraming.fragment(frame, link.maxWrite).forEach { notifyQueue.add(link.device to it) }
        }
        pumpNotifyQueue()
    }

    // ---------------------------------------------------------------- GATT server (peripheral)

    private fun startServer() {
        val server = manager.openGattServer(context, serverCallback) ?: run {
            onDiagnostic?.invoke("openGattServer returned null — can't accept incoming BLE links")
            return
        }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        // Both sides used to be unacknowledged (WRITE_NO_RESPONSE / NOTIFY) — fire-and-forget,
        // no delivery guarantee at all from the radio itself. Fine for the odd chat packet;
        // for a ~150-chunk photo transfer the odds of losing at least one chunk with zero
        // indication are real, and no amount of app-level pacing can tell "sent" from "sent
        // and silently vanished." WRITE (with response) / INDICATE give every send an actual
        // ATT-level acknowledgment, so a completion callback now means the peer really got it.
        val write = BluetoothGattCharacteristic(
            FRAME_WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notify = BluetoothGattCharacteristic(
            FRAME_NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_INDICATE,
            0,
        )
        notify.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(write)
        service.addCharacteristic(notify)
        server.addService(service)
        gattServer = server
        notifyCharacteristic = notify
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            synchronized(this@BleMeshTransport) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    serverLinks[device.address] = ServerLink(device)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    serverLinks.remove(device.address)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            synchronized(this@BleMeshTransport) {
                serverLinks[device.address]?.maxWrite = (mtu - 3).coerceAtLeast(DEFAULT_WRITE)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == FRAME_WRITE_UUID) {
                val frame = synchronized(this@BleMeshTransport) {
                    val link = serverLinks.getOrPut(device.address) { ServerLink(device) }
                    link.lastActivityMs = System.currentTimeMillis()
                    link.reassembler.accept(value)
                }
                frame?.let { onFrame?.invoke(it) }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                synchronized(this@BleMeshTransport) {
                    serverLinks.getOrPut(device.address) { ServerLink(device) }.subscribed =
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(this@BleMeshTransport) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    serverLinks[device.address]?.lastActivityMs = System.currentTimeMillis()
                }
                notifying = false
                pumpNotifyQueue()
            }
        }
    }

    /** Must hold the transport lock. */
    @Suppress("DEPRECATION")
    private fun pumpNotifyQueue() {
        if (notifying) return
        val server = gattServer ?: return
        val characteristic = notifyCharacteristic ?: return
        val (device, chunk) = notifyQueue.removeFirstOrNull() ?: return
        notifying = true
        notifyingSince = System.currentTimeMillis()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, chunk)
            } else {
                characteristic.value = chunk
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        }.onFailure {
            // Threw synchronously — onNotificationSent will never fire for this one, so
            // nothing else would ever un-stick the queue without this.
            notifying = false
            pumpNotifyQueue()
        }
    }

    // ---------------------------------------------------------------- advertising

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            onDiagnostic?.invoke("BLE advertise failed to start (error $errorCode) — other phones won't see us")
        }
    }

    private fun startAdvertising() {
        val advertiser = manager.adapter?.bluetoothLeAdvertiser ?: run {
            onDiagnostic?.invoke("BLE advertiser unavailable — other phones won't see us")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        // NodeId travels in the scan response: 128-bit UUID + service data exceed one adv PDU.
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(SERVICE_UUID), selfId.toBytes())
            .build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    // ---------------------------------------------------------------- scanning + client links

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            if (data.size < 8) return
            val peerId = NodeId.fromBytes(data)
            if (peerId == selfId) return
            // Tie-break: the smaller id dials; the larger id answers via its GATT server.
            if (selfId.raw.toULong() >= peerId.raw.toULong()) return
            maybeConnect(peerId, result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            onDiagnostic?.invoke("BLE scan failed to start (error $errorCode) — we won't see other phones")
        }
    }

    private fun startScanning() {
        val scanner = manager.adapter?.bluetoothLeScanner ?: run {
            onDiagnostic?.invoke("BLE scanner unavailable — we won't see other phones")
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // SOS-mode duty cycle; adaptive later
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    @Synchronized
    private fun maybeConnect(peerId: NodeId, device: BluetoothDevice) {
        if (!started || clientLinks.containsKey(device.address)) return
        val link = ClientLink(peerId, device)
        clientLinks[device.address] = link
        link.gatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val link = synchronized(this@BleMeshTransport) { clientLinks[gatt.device.address] }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(REQUESTED_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(this@BleMeshTransport) { clientLinks.remove(gatt.device.address) }
                runCatching { gatt.close() }
                link?.takeIf { it.ready }?.let { onPeerDisconnected?.invoke(it.peerId) }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            synchronized(this@BleMeshTransport) {
                clientLinks[gatt.device.address]?.maxWrite = (mtu - 3).coerceAtLeast(DEFAULT_WRITE)
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID) ?: return
            val link = synchronized(this@BleMeshTransport) { clientLinks[gatt.device.address] } ?: return
            link.writeCharacteristic = service.getCharacteristic(FRAME_WRITE_UUID)
            val notify = service.getCharacteristic(FRAME_NOTIFY_UUID) ?: return
            gatt.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD_UUID) ?: return
            writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            val link = synchronized(this@BleMeshTransport) { clientLinks[gatt.device.address] } ?: return
            link.ready = true
            onPeerConnected?.invoke(link.peerId)
            synchronized(this@BleMeshTransport) { pumpQueue(link) }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(this@BleMeshTransport) {
                clientLinks[gatt.device.address]?.let {
                    if (status == BluetoothGatt.GATT_SUCCESS) it.lastActivityMs = System.currentTimeMillis()
                    it.writing = false
                    pumpQueue(it)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(gatt, value)
        }

        @Deprecated("pre-T callback")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleNotification(gatt, characteristic.value ?: return)
            }
        }
    }

    private fun handleNotification(gatt: BluetoothGatt, value: ByteArray) {
        val frame = synchronized(this@BleMeshTransport) {
            val link = clientLinks[gatt.device.address] ?: return@synchronized null
            link.lastActivityMs = System.currentTimeMillis()
            link.reassembler.accept(value)
        }
        frame?.let { onFrame?.invoke(it) }
    }

    /** Must hold the transport lock. */
    @Suppress("DEPRECATION")
    private fun pumpQueue(link: ClientLink) {
        if (link.writing || !link.ready) return
        val chunk = link.queue.removeFirstOrNull() ?: return
        val gatt = link.gatt ?: return
        val characteristic = link.writeCharacteristic ?: return
        link.writing = true
        link.writingSince = System.currentTimeMillis()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = chunk
                gatt.writeCharacteristic(characteristic)
            }
        }.onFailure { link.writing = false }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }
}
