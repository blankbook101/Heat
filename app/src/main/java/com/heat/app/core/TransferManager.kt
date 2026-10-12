package com.heat.app.core

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Fail(message: String) : Exception(message)

/**
 * One transfer at a time.
 *   sender   : OFFER (BLE) -> ACCEPT with Wi-Fi Direct credentials (BLE) -> join group -> TCP -> ack
 *   receiver : accept -> create group + listen -> ACCEPT (BLE) -> receive into Downloads/Heat
 * When it ends (any outcome) the group, sockets and BLE hold are torn down, so nothing stays on the network.
 */
class TransferManager(private val e: HeatEngine) {
    private val ctx = e.app
    private val wifi = WifiDirect(ctx)

    private var job: Job? = null
    private var sid = ""
    private var peerId = ""
    private var uris: List<Uri> = emptyList()
    private var names: List<String> = emptyList()
    private var sizes: List<Long> = emptyList()

    @Volatile private var cancelled = false
    @Volatile private var cancelMsg: String? = null
    @Volatile private var sock: Socket? = null
    @Volatile private var srv: ServerSocket? = null
    private var wake: PowerManager.WakeLock? = null
    private var timeout: Runnable? = null

    // ------------------------------------------------------------------ helpers

    fun estimate(total: Long): Long = (6 + total / e.prefs.throughput).toLong().coerceAtLeast(3)

    private fun label(n: List<String>) = if (n.size > 1) "${n[0]} +${n.size - 1} more" else n.firstOrNull() ?: "file"

    private fun set(ui: TransferUi) { e.transfer.value = ui }

    private fun update(f: TransferUi.() -> TransferUi) {
        e.transfer.update { cur -> if (cur != null && cur.sid == sid) cur.f() else cur }
    }

    private fun armTimeout(ms: Long, action: () -> Unit) {
        cancelTimeout()
        val r = Runnable { timeout = null; action() }
        timeout = r
        e.main.postDelayed(r, ms)
    }

    private fun cancelTimeout() {
        timeout?.let { e.main.removeCallbacks(it) }
        timeout = null
    }

    private fun json(vararg kv: Pair<String, Any>): String {
        val o = JSONObject().put("id", e.prefs.id)
        kv.forEach { o.put(it.first, it.second) }
        return o.toString()
    }

    private fun failUi(msg: String) {
        val ui = e.transfer.value
        update { copy(phase = Phase.FAILED, message = msg) }
        if (ui != null && ui.sid == sid) e.notifier.failed("File transfer failed", "${ui.label}: $msg")
    }

    /** Describes the picked files: (display name, size). Runs on IO. */
    fun describe(picked: List<Uri>): List<Pair<String, Long>> {
        val cr = ctx.contentResolver
        return picked.map { u ->
            var name = "file"
            var size = -1L
            runCatching {
                cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            if (size < 0) size = runCatching { cr.openFileDescriptor(u, "r")?.use { it.statSize } ?: -1L }.getOrDefault(-1L)
            name to size.coerceAtLeast(0)
        }
    }

    private fun wakeLock(on: Boolean) {
        if (on) {
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heat:transfer").also { it.acquire(15 * 60 * 1000L) }
        } else {
            runCatching { wake?.takeIf { it.isHeld }?.release() }
            wake = null
        }
    }

    private inner class Tracker(val total: Long) {
        var done = 0L
        private var lastEmit = 0L
        private var winT = SystemClock.elapsedRealtime()
        private var winB = 0L
        private var speed = 0.0
        val t0 = SystemClock.elapsedRealtime()

        fun add(n: Int) {
            done += n
            val t = SystemClock.elapsedRealtime()
            if (t - lastEmit >= 250) {
                val dt = (t - winT) / 1000.0
                if (dt >= 0.5) {
                    val inst = (done - winB) / dt
                    speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                    winT = t
                    winB = done
                }
                lastEmit = t
                emit()
            }
        }

        fun emit() {
            val eta = if (speed > 1) ((total - done) / speed).toLong() else -1L
            update {
                if (phase == Phase.TRANSFERRING) copy(doneBytes = done, speedBps = speed, etaSec = eta) else this
            }
        }

        fun recordThroughput() {
            val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
            if (secs > 1.5 && done > 512 * 1024) {
                val avg = done / secs
                e.prefs.throughput = (0.5 * e.prefs.throughput + 0.5 * avg).coerceIn(1e6, 5e7)
            }
        }
    }

    // ------------------------------------------------------------------ sender

    fun startSend(peer: String, peerName: String, picked: List<Uri>) {
        if (e.transfer.value?.phase?.active == true) return
        if (e.groups.busy()) {
            sid = randomId(8)
            peerId = peer
            set(
                TransferUi(
                    sid, peer, peerName, true, "files", picked.size, 0, Phase.FAILED,
                    message = "You're in a group. Leave it before sending single files."
                )
            )
            return
        }
        e.scope.launch {
            val info = withContext(Dispatchers.IO) {
                picked.forEach { u ->
                    runCatching {
                        ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                describe(picked)
            }
            if (info.isEmpty()) return@launch
            val total = info.sumOf { it.second }
            sid = randomId(8)
            peerId = peer
            uris = picked
            names = info.map { it.first }
            sizes = info.map { it.second }
            cancelled = false
            cancelMsg = null
            val lbl = label(names)
            set(TransferUi(sid, peer, peerName, true, lbl, names.size, total, Phase.WAITING, estimateSec = estimate(total)))
            e.ble.hold(peer, true)
            val offer = json(
                "name" to e.name.value, "sid" to sid, "count" to names.size, "bytes" to total, "first" to names[0]
            )
            e.ble.send(peer, P.T_OFFER, offer) {
                e.main.post {
                    cancelTimeout()
                    failUi("Couldn't reach $peerName over Bluetooth")
                    e.ble.hold(peer, false)
                }
            }
            armTimeout(90_000) {
                if (e.transfer.value?.phase == Phase.WAITING) {
                    e.ble.send(peer, P.T_CANCEL, json("sid" to sid)) {}
                    failUi("$peerName didn't respond")
                    e.ble.hold(peer, false)
                }
            }
        }
    }

    fun onAccept(id: String, o: JSONObject) {
        val ui = e.transfer.value ?: return
        if (!ui.outgoing || ui.phase != Phase.WAITING || o.optString("sid") != sid || id != peerId) return
        cancelTimeout()
        update { copy(phase = Phase.CONNECTING) }
        val ssid = o.optString("ssid")
        val pass = o.optString("pass")
        launchFlow { sendFlow(ssid, pass) }
    }

    private suspend fun sendFlow(ssid: String, pass: String) {
        val total = e.transfer.value?.totalBytes ?: 0L
        if (!wifi.wifiOn()) throw Fail("Turn on Wi-Fi to send files")
        val host = wifi.join(ssid, pass, 25_000) ?: throw Fail("Couldn't join the Wi-Fi Direct link")
        if (cancelled) throw Fail("Cancelled")
        var s: Socket? = null
        for (i in 0 until 10) {
            try {
                val t = Socket()
                t.connect(InetSocketAddress(host, P.TCP_PORT), 3000)
                s = t
                break
            } catch (x: Throwable) {
                if (cancelled) break
                delay(700)
            }
        }
        val socket = s ?: throw Fail("Couldn't connect to the receiver")
        sock = socket
        socket.soTimeout = 30_000
        update { copy(phase = Phase.TRANSFERRING) }
        val tr = Tracker(total)
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), P.BUF))
        out.writeInt(P.MAGIC)
        out.writeUTF(sid)
        out.writeInt(uris.size)
        val buf = ByteArray(P.BUF)
        for (i in uris.indices) {
            out.writeUTF(names[i])
            out.writeLong(sizes[i])
            val ins = ctx.contentResolver.openInputStream(uris[i]) ?: throw Fail("Couldn't open ${names[i]}")
            ins.use { input ->
                var left = sizes[i]
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw Fail("${names[i]} changed while sending")
                    out.write(buf, 0, n)
                    left -= n
                    tr.add(n)
                }
            }
        }
        out.flush()
        val ack = DataInputStream(socket.getInputStream()).readInt()
        if (ack != 1) throw Fail("The receiver didn't confirm")
        tr.recordThroughput()
        val ui = e.transfer.value
        update { copy(phase = Phase.DONE, doneBytes = total, etaSec = 0, message = "Sent to ${peerName}") }
        e.notifier.done("File transfer successful", "${ui?.label ?: "File"} sent to ${ui?.peerName ?: "device"}")
        e.addChat(peerId, ChatMsg(2, "Sent ${ui?.label ?: "file"}", System.currentTimeMillis()))
    }

    fun onDecline(id: String, o: JSONObject) {
        val ui = e.transfer.value ?: return
        if (!ui.outgoing || o.optString("sid") != sid || id != peerId || !ui.phase.active) return
        cancelTimeout()
        update { copy(phase = Phase.DECLINED, message = "${ui.peerName} declined") }
        e.ble.hold(peerId, false)
    }

    // ------------------------------------------------------------------ receiver

    fun onOffer(id: String, peerName: String, o: JSONObject) {
        val sidIn = o.optString("sid")
        val cur = e.transfer.value
        if ((cur != null && cur.phase.active) || e.groups.busy()) {
            e.ble.send(id, P.T_DECLINE, json("sid" to sidIn)) {}
            return
        }
        sid = sidIn
        peerId = id
        val total = o.optLong("bytes")
        val count = o.optInt("count", 1)
        val first = o.optString("first", "file")
        val lbl = if (count > 1) "$first +${count - 1} more" else first
        cancelled = false
        cancelMsg = null
        set(TransferUi(sid, id, peerName, false, lbl, count, total, Phase.WAITING, estimateSec = estimate(total)))
        e.notifier.incoming(peerName, "$lbl · ${fmtBytes(total)}")
        armTimeout(120_000) {
            if (e.transfer.value?.phase == Phase.WAITING && !(e.transfer.value?.outgoing ?: true)) declineIncoming()
        }
    }

    fun declineIncoming() {
        val ui = e.transfer.value ?: return
        if (ui.outgoing || ui.phase != Phase.WAITING) return
        cancelTimeout()
        e.ble.send(peerId, P.T_DECLINE, json("sid" to sid)) {}
        e.transfer.value = null
    }

    fun acceptIncoming() {
        val ui = e.transfer.value ?: return
        if (ui.outgoing || ui.phase != Phase.WAITING) return
        cancelTimeout()
        update { copy(phase = Phase.CONNECTING) }
        e.ble.hold(peerId, true)
        launchFlow { receiveFlow() }
    }

    private suspend fun receiveFlow() {
        val total = e.transfer.value?.totalBytes ?: 0L
        if (!wifi.wifiOn()) throw Fail("Turn on Wi-Fi to receive files")
        val ssid = "DIRECT-HT-" + randomId(4, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        val pass = randomId(12, "abcdefghjkmnpqrstuvwxyz23456789")
        if (!wifi.createGroup(ssid, pass)) throw Fail("Wi-Fi Direct couldn't start. Keep Wi-Fi (and Location on Android 12) on")
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(P.TCP_PORT))
        server.soTimeout = 45_000
        srv = server
        e.ble.send(peerId, P.T_ACCEPT, json("sid" to sid, "ssid" to ssid, "pass" to pass)) {
            e.main.post { cancelled = true; cancelMsg = "Couldn't reach the sender over Bluetooth"; runCatching { server.close() } }
        }
        val s = try { server.accept() } catch (t: java.net.SocketTimeoutException) { throw Fail("The sender never connected") }
        sock = s
        s.soTimeout = 20_000
        update { copy(phase = Phase.TRANSFERRING) }
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), P.BUF))
        if (inp.readInt() != P.MAGIC) throw Fail("Not a Heat transfer")
        if (inp.readUTF() != sid) throw Fail("Wrong transfer session")
        val count = inp.readInt()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val tr = Tracker(total)
        val buf = ByteArray(P.BUF)
        repeat(count) {
            val name = clean(inp.readUTF())
            val size = inp.readLong()
            val (uri, os) = openOut("${stamp}_$name", mimeOf(name))
            try {
                var left = size
                while (left > 0) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw Fail("Connection closed early")
                    os.write(buf, 0, n)
                    left -= n
                    tr.add(n)
                }
                os.flush()
                os.close()
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                runCatching { os.close() }
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                throw t
            }
        }
        val out = DataOutputStream(s.getOutputStream())
        out.writeInt(1)
        out.flush()
        tr.recordThroughput()
        val ui = e.transfer.value
        update { copy(phase = Phase.DONE, doneBytes = total, etaSec = 0, message = "Saved in Downloads/Heat") }
        e.notifier.done("File transfer successful", "${ui?.label ?: "File"} from ${ui?.peerName ?: "device"} saved in Downloads/Heat")
        e.addChat(peerId, ChatMsg(2, "Received ${ui?.label ?: "file"}", System.currentTimeMillis()))
    }

    private fun clean(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "file" }.take(120)

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun openOut(display: String, mime: String): Pair<Uri, OutputStream> {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, display)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Heat")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw Fail("Couldn't create the file")
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw Fail("Couldn't open the file for writing")
        return uri to BufferedOutputStream(os, P.BUF)
    }

    // ------------------------------------------------------------------ shared

    private fun launchFlow(block: suspend () -> Unit) {
        wakeLock(true)
        job = e.scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (t: Throwable) {
                handleError(t)
            } finally {
                runCatching { sock?.close() }
                runCatching { srv?.close() }
                sock = null
                srv = null
                withContext(NonCancellable) { wifi.clear() }
                e.ble.hold(peerId, false)
                wakeLock(false)
                job = null
            }
        }
    }

    private fun handleError(t: Throwable) {
        val ui = e.transfer.value
        if (ui == null || ui.sid != sid) return
        if (cancelled) {
            update { copy(phase = Phase.CANCELLED, message = cancelMsg ?: "Cancelled") }
        } else {
            failUi(if (t is Fail) t.message ?: "Failed" else "Transfer interrupted")
        }
    }

    fun cancel() {
        val ui = e.transfer.value ?: return
        if (!ui.phase.active) return
        if (!ui.outgoing && ui.phase == Phase.WAITING) { declineIncoming(); return }
        cancelled = true
        cancelMsg = "Cancelled"
        cancelTimeout()
        e.ble.send(peerId, P.T_CANCEL, json("sid" to sid)) {}
        runCatching { sock?.close() }
        runCatching { srv?.close() }
        if (job == null) {
            update { copy(phase = Phase.CANCELLED, message = "Cancelled") }
            e.ble.hold(peerId, false)
        }
    }

    fun onCancelRemote(id: String, o: JSONObject) {
        val ui = e.transfer.value ?: return
        if (o.optString("sid") != sid || id != peerId || !ui.phase.active) return
        cancelled = true
        cancelMsg = "${ui.peerName} cancelled"
        cancelTimeout()
        runCatching { sock?.close() }
        runCatching { srv?.close() }
        if (job == null) {
            if (!ui.outgoing && ui.phase == Phase.WAITING) {
                e.transfer.value = null
            } else {
                update { copy(phase = Phase.CANCELLED, message = "${ui.peerName} cancelled") }
            }
            e.ble.hold(peerId, false)
        }
    }

    fun dismiss() {
        val ui = e.transfer.value ?: return
        if (!ui.phase.active) e.transfer.value = null
    }
}
