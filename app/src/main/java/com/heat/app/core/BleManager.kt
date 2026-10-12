package com.heat.app.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import org.json.JSONObject

/**
 * BLE layer.
 *  - Every phone advertises the Heat service (id + username in the scan response) and runs a
 *    small GATT server (INBOX = peers write, OUTBOX = we notify).
 *  - Scanning only runs while the Nearby screen is open.
 *  - Frames (chat, file offers, Wi-Fi Direct credentials) travel over GATT. All state is touched
 *    on the main thread only.
 */
@SuppressLint("MissingPermission")
class BleManager(
    private val ctx: Context,
    private val myId: () -> String,
    private val myName: () -> String,
    private val myFlags: () -> Int,
    private val myVer: () -> Int,
    private val onSeen: (id: String, name: String, flags: Int, ver: Int, device: BluetoothDevice, rssi: Int) -> Unit,
    private val onFrame: (peerId: String, type: Int, json: String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val btm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = btm.adapter

    val enabled: Boolean get() = adapter?.isEnabled == true

    private var server: BluetoothGattServer? = null
    private var outbox: BluetoothGattCharacteristic? = null
    private var advCb: AdvertiseCallback? = null
    private var advBoost = false

    private var scanCb: ScanCallback? = null
    private var scanning = false
    private var scanLowLatency = true

    private val devices = HashMap<String, BluetoothDevice>()    // peerId -> device (from scans)
    private val clientLinks = HashMap<String, Link>()           // peerId -> link we opened
    private val held = HashSet<String>()                        // peers we keep connected

    // server side
    private val serverDevices = HashMap<String, BluetoothDevice>() // address -> device
    private val serverRoutes = HashMap<String, String>()           // peerId -> address
    private val subscribed = HashSet<String>()
    private val serverMtu = HashMap<String, Int>()
    private val reassemblers = HashMap<String, Reassembler>()
    private val notifyQ = HashMap<String, ArrayDeque<ByteArray>>()
    private val notifyBusy = HashSet<String>()

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (!enabled) return
        if (server == null) openServer()
        refreshAdvertising()
    }

    fun shutdown() {
        stopScan()
        advCb?.let { cb -> runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) } }
        advCb = null
        clientLinks.values.toList().forEach { it.close() }
        clientLinks.clear()
        runCatching { server?.close() }
        server = null
        outbox = null
        serverDevices.clear(); serverRoutes.clear(); subscribed.clear(); serverMtu.clear()
        reassemblers.clear(); notifyQ.clear(); notifyBusy.clear(); held.clear()
    }

    // ---------------------------------------------------------------- advertising

    fun setAdvBoost(on: Boolean) {
        if (advBoost == on) return
        advBoost = on
        if (server != null) refreshAdvertising()
    }

    fun refreshAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        advCb?.let { runCatching { adv.stopAdvertising(it) } }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                if (advBoost) AdvertiseSettings.ADVERTISE_MODE_BALANCED else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            )
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(P.SERVICE))
            .build()
        // 27 bytes max: id(4) + flags(1) + photo version(1) + name(<=21)
        val payload = hexToBytes(myId()) +
            byteArrayOf(myFlags().toByte(), myVer().toByte()) +
            trimUtf8(myName(), 21)
        val scanResp = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(P.COMPANY_ID, payload)
            .build()
        val cb = object : AdvertiseCallback() {}
        advCb = cb
        runCatching { adv.startAdvertising(settings, data, scanResp, cb) }
    }

    // ---------------------------------------------------------------- scanning

    private val downshift = Runnable {
        if (scanning && scanLowLatency) {
            scanLowLatency = false
            restartScan()
        }
    }

    fun startScan() {
        if (!enabled || scanning) return
        scanLowLatency = true
        beginScan()
        main.postDelayed(downshift, 12_000)   // fast for 12 s, then battery-friendly
    }

    private fun beginScan() {
        val sc = adapter?.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(P.SERVICE)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(if (scanLowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handleScan(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handleScan(it) }
        }
        scanCb = cb
        scanning = true
        runCatching { sc.startScan(filters, settings, cb) }
    }

    private fun restartScan() {
        scanCb?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        scanCb = null
        beginScan()
    }

    fun stopScan() {
        main.removeCallbacks(downshift)
        scanning = false
        scanCb?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        scanCb = null
    }

    private fun handleScan(r: ScanResult) {
        val md = r.scanRecord?.getManufacturerSpecificData(P.COMPANY_ID) ?: return
        if (md.size < 6) return
        val id = bytesToHex(md, 0, 4)
        if (id == myId()) return
        val flags = md[4].toInt() and 0xFF
        val ver = md[5].toInt() and 0xFF
        val name = String(md, 6, md.size - 6, Charsets.UTF_8).ifBlank { "Heat user" }
        val dev = r.device
        val rssi = r.rssi
        main.post {
            devices[id] = dev
            onSeen(id, name, flags, ver, dev, rssi)
        }
    }

    // ---------------------------------------------------------------- GATT server

    private fun openServer() {
        val s = btm.openGattServer(ctx, serverCb) ?: return
        val svc = BluetoothGattService(P.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val inbox = BluetoothGattCharacteristic(
            P.INBOX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val out = BluetoothGattCharacteristic(
            P.OUTBOX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ
        )
        out.addDescriptor(
            BluetoothGattDescriptor(
                P.CCC, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        svc.addCharacteristic(inbox)
        svc.addCharacteristic(out)
        outbox = out
        server = s
        s.addService(svc)
    }

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post {
                    val a = device.address
                    serverDevices.remove(a); subscribed.remove(a); serverMtu.remove(a)
                    reassemblers.remove("s$a"); notifyQ.remove(a); notifyBusy.remove(a)
                    serverRoutes.entries.removeAll { it.value == a }
                }
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { serverDevices[device.address] = device }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            main.post { serverMtu[device.address] = mtu }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val ok = characteristic.uuid == P.INBOX && value != null
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (ok) android.bluetooth.BluetoothGatt.GATT_SUCCESS else android.bluetooth.BluetoothGatt.GATT_FAILURE,
                    offset, value
                )
            }
            if (ok) {
                val v = value!!.copyOf()
                main.post { handleChunk("s${device.address}", device, true, v) }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == P.CCC && value != null) {
                val on = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                main.post {
                    serverDevices[device.address] = device
                    if (on) subscribed.add(device.address) else subscribed.remove(device.address)
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post { notifyNext(device.address) }
        }
    }

    private fun enqueueNotify(addr: String, chunks: List<ByteArray>) {
        notifyQ.getOrPut(addr) { ArrayDeque() }.addAll(chunks)
        if (!notifyBusy.contains(addr)) notifyNext(addr)
    }

    private fun notifyNext(addr: String) {
        val q = notifyQ[addr]
        val dev = serverDevices[addr]
        val ch = outbox
        val srv = server
        val bytes = q?.removeFirstOrNull()
        if (q == null || dev == null || ch == null || srv == null || bytes == null) {
            notifyBusy.remove(addr)
            return
        }
        notifyBusy.add(addr)
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            srv.notifyCharacteristicChanged(dev, ch, false, bytes) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { ch.value = bytes; srv.notifyCharacteristicChanged(dev, ch, false) }
        }
        if (!ok) {
            q.clear()
            notifyBusy.remove(addr)
        }
    }

    // ---------------------------------------------------------------- inbound frames

    private fun handleChunk(key: String, device: BluetoothDevice?, viaServer: Boolean, bytes: ByteArray) {
        val r = reassemblers.getOrPut(key) { Reassembler() }
        val (type, json) = r.feed(bytes) ?: return
        val id = try { JSONObject(json).optString("id") } catch (_: Exception) { "" }
        if (id.isEmpty()) return
        if (viaServer && device != null) {
            serverDevices[device.address] = device
            serverRoutes[id] = device.address
        }
        onFrame(id, type, json)
    }

    // ---------------------------------------------------------------- outbound frames

    /** Keeps the BLE connection to [peerId] open while a transfer needs the control channel. */
    fun hold(peerId: String, on: Boolean) {
        main.post {
            if (on) held.add(peerId) else {
                held.remove(peerId)
                clientLinks[peerId]?.touch()
            }
        }
    }

    fun send(peerId: String, type: Int, json: String, onFail: () -> Unit) {
        main.post {
            val addr = serverRoutes[peerId]
            if (addr != null && subscribed.contains(addr) && serverDevices[addr] != null) {
                enqueueNotify(addr, Framing.chunks(type, json, (serverMtu[addr] ?: 23) - 3))
                return@post
            }
            val link = clientLinks[peerId]
                ?: devices[peerId]?.let { d -> Link(peerId, d).also { clientLinks[peerId] = it } }
            if (link == null) {
                onFail()
                return@post
            }
            link.enqueue(type, json, onFail)
        }
    }

    // ---------------------------------------------------------------- GATT client link

    private inner class Item(val type: Int, val json: String, val onFail: () -> Unit) {
        var chunks: ArrayDeque<ByteArray>? = null
    }

    private inner class Link(val peerId: String, val device: BluetoothDevice) {
        var gatt: BluetoothGatt? = null
        var ready = false
        var busy = false
        var mtu = 23
        var inbox: BluetoothGattCharacteristic? = null
        val items = ArrayDeque<Item>()

        private val idle = Runnable { if (held.contains(peerId)) touch() else close() }

        fun touch() {
            main.removeCallbacks(idle)
            main.postDelayed(idle, 90_000)
        }

        fun enqueue(type: Int, json: String, onFail: () -> Unit) {
            items.addLast(Item(type, json, onFail))
            touch()
            if (gatt == null) {
                gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            } else if (ready) {
                pump()
            }
        }

        fun pump() {
            if (!ready || busy) return
            val g = gatt ?: return
            val ch = inbox ?: return
            val item = items.firstOrNull() ?: return
            val cs = item.chunks ?: ArrayDeque(Framing.chunks(item.type, item.json, mtu - 3)).also { item.chunks = it }
            val bytes = cs.removeFirstOrNull()
            if (bytes == null) {
                items.removeFirst()
                pump()
                return
            }
            busy = true
            if (!write(g, ch, bytes)) {
                busy = false
                fail()
            }
        }

        fun fail() {
            val pending = items.toList()
            items.clear()
            close()
            pending.forEach { it.onFail() }
        }

        fun close() {
            main.removeCallbacks(idle)
            runCatching { gatt?.close() }
            gatt = null
            ready = false
            busy = false
            reassemblers.remove("c$peerId")
            if (clientLinks[peerId] === this) clientLinks.remove(peerId)
        }

        private fun write(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray): Boolean =
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = bytes
                    g.writeCharacteristic(ch)
                }
            }

        private fun enableNotify(g: BluetoothGatt, out: BluetoothGattCharacteristic) {
            g.setCharacteristicNotification(out, true)
            val d = out.getDescriptor(P.CCC)
            if (d == null) {
                ready = true
                pump()
                return
            }
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
            }
            if (!started) {
                ready = true
                pump()
            }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    main.post {
                        if (gatt === g) {
                            if (!g.requestMtu(247)) g.discoverServices()
                        }
                    }
                } else {
                    main.post { if (gatt === g) fail() else runCatching { g.close() } }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
                main.post {
                    if (gatt !== g) return@post
                    mtu = if (status == BluetoothGatt.GATT_SUCCESS) m else 23
                    g.discoverServices()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                main.post {
                    if (gatt !== g) return@post
                    val svc = g.getService(P.SERVICE)
                    val inb = svc?.getCharacteristic(P.INBOX)
                    val out = svc?.getCharacteristic(P.OUTBOX)
                    if (status != BluetoothGatt.GATT_SUCCESS || inb == null || out == null) {
                        fail()
                    } else {
                        inbox = inb
                        enableNotify(g, out)
                    }
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                main.post {
                    if (gatt !== g) return@post
                    ready = true
                    pump()
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                main.post {
                    if (gatt !== g) return@post
                    busy = false
                    if (status == BluetoothGatt.GATT_SUCCESS) pump() else fail()
                }
            }

            // Android 13+
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                val v = value.copyOf()
                main.post { handleChunk("c$peerId", device, false, v) }
            }

            // Android 12 and lower
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= 33) return
                @Suppress("DEPRECATION")
                val v = c.value?.copyOf() ?: return
                main.post { handleChunk("c$peerId", device, false, v) }
            }
        }
    }
}
