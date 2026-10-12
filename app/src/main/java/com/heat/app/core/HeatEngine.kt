package com.heat.app.core

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Glue between BLE, Wi-Fi Direct transfers, groups, notifications and the UI. Lives as long as the process. */
class HeatEngine(val app: Application) {
    val prefs = Prefs(app)
    val main = Handler(Looper.getMainLooper())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val notifier = Notifier(app)
    val burner = Burner(app)

    val name = MutableStateFlow(prefs.name)
    val themeMode = MutableStateFlow(prefs.themeMode)
    val termsOk = MutableStateFlow(prefs.termsAccepted)
    val btOn = MutableStateFlow(false)
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val chats = MutableStateFlow<Map<String, List<ChatMsg>>>(emptyMap())
    val unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val transfer = MutableStateFlow<TransferUi?>(null)
    val openPeer = MutableStateFlow<String?>(null)

    // profile / easter egg
    val eggFound = MutableStateFlow(prefs.egg)
    val avatarMode = MutableStateFlow(prefs.avatarMode)
    val myAvatar = MutableStateFlow<ImageBitmap?>(null)
    val peerAvatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    // group state (driven by GroupManager)
    val groupUi = MutableStateFlow<GroupUi?>(null)

    val names = HashMap<String, String>()
    val peerFlags = HashMap<String, Int>()
    private val avatarVers = HashMap<String, Int>()
    private val avAsked = HashMap<String, Long>()
    private val avQueue = Channel<String>(Channel.UNLIMITED)
    @Volatile var visible = false
    @Volatile var openChat: String? = null

    val ble = BleManager(app, { prefs.id }, { name.value }, { myFlags() }, { prefs.avatarVer }, ::onSeen, ::onFrame)
    val transfers = TransferManager(this)
    val groups = GroupManager(this)

    private var started = false
    private var discovering = false
    private val lastSeen = HashMap<String, Long>()

    init {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> {
                        btOn.value = true
                        if (started && hasCorePerms()) {
                            ble.start()
                            if (discovering) ble.startScan()
                        }
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        btOn.value = false
                        ble.shutdown()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            app, rx, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        myAvatar.value = loadAvatar()
        if (avatarMode.value == 1 && myAvatar.value == null) setMode(0)
        if (avatarMode.value == 2 && !eggFound.value) setMode(0)

        // fetch other people's photos one at a time so BLE isn't flooded
        scope.launch {
            for (pid in avQueue) {
                ble.send(pid, P.T_AVREQ, JSONObject().put("id", prefs.id).toString()) {}
                delay(2500)
            }
        }
    }

    // ------------------------------------------------------------------ permissions / lifecycle

    fun hasCorePerms(): Boolean {
        fun ok(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
        val bt = ok(Manifest.permission.BLUETOOTH_SCAN) && ok(Manifest.permission.BLUETOOTH_ADVERTISE) &&
            ok(Manifest.permission.BLUETOOTH_CONNECT)
        val wifi = if (Build.VERSION.SDK_INT >= 33) ok(Manifest.permission.NEARBY_WIFI_DEVICES)
        else ok(Manifest.permission.ACCESS_FINE_LOCATION)
        return bt && wifi
    }

    fun acceptTerms() {
        prefs.termsAccepted = true
        termsOk.value = true
    }

    fun start() {
        if (!termsOk.value || !hasCorePerms()) return
        started = true
        btOn.value = ble.enabled
        if (ble.enabled) ble.start()
    }

    fun stop() {
        groups.leave()
        started = false
        stopDiscovery()
        ble.shutdown()
    }

    fun refreshAdvertising() {
        if (started) ble.refreshAdvertising()
    }

    fun setName(n: String) {
        val v = n.trim().take(16)
        if (v.isEmpty()) return
        prefs.name = v
        name.value = v
        if (started) {
            ble.refreshAdvertising()
            notifier.updateService(v)
        }
    }

    fun cycleTheme() {
        val n = (themeMode.value + 1) % 3
        prefs.themeMode = n
        themeMode.value = n
    }

    fun setAdvBoost(on: Boolean) = ble.setAdvBoost(on)

    // ------------------------------------------------------------------ profile / easter egg

    /** bit0 egg found, bit1 sharing a photo, bit2 secret pfp, bit3 leading an open group. */
    fun myFlags(): Int {
        var f = 0
        if (eggFound.value) f = f or 1
        if (avatarMode.value == 1 && myAvatar.value != null) f = f or 2
        if (avatarMode.value == 2 && eggFound.value) f = f or 4
        if (groups.leadingOpen()) f = f or 8
        return f
    }

    private fun avatarFile() = File(app.filesDir, "avatar.jpg")

    private fun loadAvatar(): ImageBitmap? = try {
        val f = avatarFile()
        if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
    } catch (_: Exception) {
        null
    }

    private fun setMode(m: Int) {
        prefs.avatarMode = m
        avatarMode.value = m
    }

    fun setAvatarMode(m: Int) {
        if (m == 1 && myAvatar.value == null) return
        if (m == 2 && !eggFound.value) return
        setMode(m)
        main.post { refreshAdvertising() }
    }

    /** Crops to a square, shrinks to 96x96 and stores a tiny JPEG (small enough to send over Bluetooth). */
    fun setAvatar(uri: Uri): Boolean {
        try {
            val cr = app.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 192 && bounds.outHeight / (sample * 2) >= 192) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
            val side = minOf(bmp.width, bmp.height)
            val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val small = Bitmap.createScaledBitmap(square, 96, 96, true)
            var q = 70
            var bytes: ByteArray
            do {
                val bo = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, q, bo)
                bytes = bo.toByteArray()
                q -= 10
            } while (bytes.size > 4500 && q >= 20)
            avatarFile().writeBytes(bytes)
            prefs.avatarVer = prefs.avatarVer + 1
            myAvatar.value = small.asImageBitmap()
            setMode(1)
            main.post { refreshAdvertising() }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun removeAvatar() {
        runCatching { avatarFile().delete() }
        myAvatar.value = null
        prefs.avatarVer = prefs.avatarVer + 1
        if (avatarMode.value == 1) setMode(0)
        main.post { refreshAdvertising() }
    }

    fun foundEgg() {
        if (eggFound.value) return
        prefs.egg = true
        eggFound.value = true
        main.post { refreshAdvertising() }
    }

    /** Queue a download of this peer's photo if they advertise one we don't have yet. */
    fun wantAvatar(p: Peer) {
        if (p.flags and 2 == 0) return
        if (avatarVers[p.id] == p.ver && peerAvatars.value.containsKey(p.id)) return
        val now = SystemClock.elapsedRealtime()
        if (now - (avAsked[p.id] ?: 0L) < 30_000) return
        avAsked[p.id] = now
        avQueue.trySend(p.id)
    }

    // ------------------------------------------------------------------ discovery

    fun startDiscovery() {
        if (!started) start()
        discovering = true
        peers.value = emptyList()
        lastSeen.clear()
        ble.startScan()
        main.removeCallbacks(prune)
        main.postDelayed(prune, 4000)
    }

    fun stopDiscovery() {
        discovering = false
        ble.stopScan()
        main.removeCallbacks(prune)
    }

    private val prune = object : Runnable {
        override fun run() {
            val cut = SystemClock.elapsedRealtime() - 20_000
            val cur = peers.value
            val keep = cur.filter { (lastSeen[it.id] ?: 0L) >= cut }
            if (keep.size != cur.size) peers.value = keep
            if (discovering) main.postDelayed(this, 4000)
        }
    }

    private fun onSeen(id: String, nm: String, flags: Int, ver: Int, dev: BluetoothDevice, rssi: Int) {
        val now = SystemClock.elapsedRealtime()
        lastSeen[id] = now
        names[id] = nm
        peerFlags[id] = flags
        if (!discovering) return
        val level = when {
            rssi > -60 -> 3
            rssi > -75 -> 2
            rssi > -88 -> 1
            else -> 0
        }
        val cur = peers.value
        val i = cur.indexOfFirst { it.id == id }
        if (i < 0) {
            peers.value = cur + Peer(id, nm, dev, level, now, flags, ver)
        } else {
            val p = cur[i]
            if (p.name != nm || p.level != level || p.flags != flags || p.ver != ver) {
                peers.value = cur.toMutableList().also {
                    it[i] = p.copy(name = nm, level = level, device = dev, flags = flags, ver = ver)
                }
            }
        }
    }

    // ------------------------------------------------------------------ chat

    fun addChat(id: String, m: ChatMsg) {
        chats.update { it + (id to ((it[id] ?: emptyList()) + m).takeLast(200)) }
    }

    fun enterChat(id: String) {
        openChat = id
        unread.update { it - id }
    }

    fun leaveChat() {
        openChat = null
    }

    fun sendMessage(peerId: String, text: String) {
        val t = text.trim().take(500)
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        addChat(peerId, ChatMsg(0, t, now))
        val j = JSONObject().put("id", prefs.id).put("name", name.value).put("text", t).put("ts", now)
        ble.send(peerId, P.T_MSG, j.toString()) {
            addChat(peerId, ChatMsg(2, "Couldn't deliver. Is ${names[peerId] ?: "they"} still nearby?", System.currentTimeMillis()))
        }
    }

    // ------------------------------------------------------------------ inbound frames

    private fun onFrame(id: String, type: Int, json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val nm = o.optString("name", "").ifBlank { names[id] ?: "Heat user" }
        names[id] = nm
        when (type) {
            P.T_MSG -> {
                val text = o.optString("text")
                if (text.isEmpty()) return
                addChat(id, ChatMsg(1, text, System.currentTimeMillis()))
                if (!(visible && openChat == id)) {
                    unread.update { it + (id to ((it[id] ?: 0) + 1)) }
                    notifier.message(id, nm, text)
                }
            }
            P.T_OFFER -> transfers.onOffer(id, nm, o)
            P.T_ACCEPT -> transfers.onAccept(id, o)
            P.T_DECLINE -> transfers.onDecline(id, o)
            P.T_CANCEL -> transfers.onCancelRemote(id, o)
            P.T_AVREQ -> {
                if (avatarMode.value == 1) {
                    val f = avatarFile()
                    if (f.exists()) {
                        val img = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                        val j = JSONObject().put("id", prefs.id).put("ver", prefs.avatarVer).put("img", img)
                        ble.send(id, P.T_AV, j.toString()) {}
                    }
                }
            }
            P.T_AV -> {
                val ver = o.optInt("ver", -1)
                runCatching {
                    val b = Base64.decode(o.optString("img"), Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(b, 0, b.size)?.let { bm ->
                        peerAvatars.update { it + (id to bm.asImageBitmap()) }
                        avatarVers[id] = ver
                    }
                }
            }
            P.T_GJOIN -> groups.onJoinRequest(id, nm, o.optInt("flags"))
            P.T_GACCEPT -> groups.onAccept(id, o)
            P.T_GDENY -> groups.onDeny(id, o)
        }
    }
}
