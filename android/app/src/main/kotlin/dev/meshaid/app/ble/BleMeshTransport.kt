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
import android.util.Log
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
        private const val TAG = "BleMeshTransport"
        val SERVICE_UUID: UUID = UUID.fromString("4d455348-4149-4400-a1d0-000000000001")
        val FRAME_WRITE_UUID: UUID = UUID.fromString("4d455348-4149-4400-a1d0-000000000002")
        val FRAME_NOTIFY_UUID: UUID = UUID.fromString("4d455348-4149-4400-a1d0-000000000003")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val REQUESTED_MTU = 517
        private const val DEFAULT_WRITE = 20 // MTU 23 - 3, until negotiated
    }

    override var onFrame: ((ByteArray) -> Unit)? = null
    override var onPeerConnected: ((NodeId) -> Unit)? = null
    override var onPeerDisconnected: ((NodeId) -> Unit)? = null

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var started = false

    private inner class ClientLink(val peerId: NodeId, val device: BluetoothDevice) {
        var gatt: BluetoothGatt? = null
        var writeCharacteristic: BluetoothGattCharacteristic? = null
        var maxWrite = DEFAULT_WRITE
        var ready = false
        var writing = false
        val queue = ArrayDeque<ByteArray>()
        val reassembler = BleFraming.Reassembler()
    }

    private inner class ServerLink(val device: BluetoothDevice) {
        var subscribed = false
        var maxWrite = DEFAULT_WRITE
        val reassembler = BleFraming.Reassembler()
    }

    private val clientLinks = HashMap<String, ClientLink>() // by device address
    private val serverLinks = HashMap<String, ServerLink>()

    @Synchronized
    fun linkCount(): Int = clientLinks.values.count { it.ready } + serverLinks.values.count { it.subscribed }

    @Synchronized
    override fun start() {
        if (started) return
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
    }

    @Synchronized
    override fun broadcast(frame: ByteArray) {
        for (link in clientLinks.values) {
            if (!link.ready) continue
            BleFraming.fragment(frame, link.maxWrite).forEach { link.queue.add(it) }
            pumpQueue(link)
        }
        val server = gattServer ?: return
        val characteristic = notifyCharacteristic ?: return
        for (link in serverLinks.values) {
            if (!link.subscribed) continue
            for (chunk in BleFraming.fragment(frame, link.maxWrite)) {
                notifyChunk(server, characteristic, link.device, chunk)
            }
        }
    }

    // ---------------------------------------------------------------- GATT server (peripheral)

    private fun startServer() {
        val server = manager.openGattServer(context, serverCallback) ?: run {
            Log.w(TAG, "openGattServer returned null")
            return
        }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val write = BluetoothGattCharacteristic(
            FRAME_WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notify = BluetoothGattCharacteristic(
            FRAME_NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
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
                    serverLinks.getOrPut(device.address) { ServerLink(device) }.reassembler.accept(value)
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
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyChunk(
        server: BluetoothGattServer,
        characteristic: BluetoothGattCharacteristic,
        device: BluetoothDevice,
        chunk: ByteArray,
    ) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, chunk)
            } else {
                characteristic.value = chunk
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        }
    }

    // ---------------------------------------------------------------- advertising

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise failed: $errorCode")
        }
    }

    private fun startAdvertising() {
        val advertiser = manager.adapter?.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "BLE advertiser unavailable")
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
            Log.w(TAG, "scan failed: $errorCode")
        }
    }

    private fun startScanning() {
        val scanner = manager.adapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "BLE scanner unavailable")
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
            writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
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
            clientLinks[gatt.device.address]?.reassembler?.accept(value)
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
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
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
