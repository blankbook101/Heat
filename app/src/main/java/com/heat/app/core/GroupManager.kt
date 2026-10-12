package com.heat.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

enum class GPhase { STARTING, OPEN, JOINING, CONNECTED, CLOSED, FAILED }

data class GMember(val id: String, val name: String, val flags: Int) {
    val gold: Boolean get() = flags and 1 != 0
    val egg: Boolean get() = flags and 4 != 0
}

data class GShare(
    val label: String,
    val total: Long,
    val done: Long,
    val speedBps: Double,
    val etaSec: Long,
    val estimateSec: Long,
    val finished: Boolean,
    val note: String? = null
)

data class GroupUi(
    val leading: Boolean,
    val leaderId: String,
    val leaderName: String,
    val leaderFlags: Int,
    val phase: GPhase,
    val members: List<GMember> = emptyList(),   // everyone except the leader (for a member this includes "me")
    val pending: List<GMember> = emptyList(),   // join requests waiting for the leader (leader only)
    val log: List<String> = emptyList(),
    val share: GShare? = null,
    val message: String? = null
) {
    val live: Boolean get() = phase != GPhase.CLOSED && phase != GPhase.FAILED
}

/**
 * Group file sharing.
 *
 *  - The leader creates a Wi-Fi Direct group (random name/passphrase), runs a small TCP hub and
 *    shows a flag in its Bluetooth advertisement so nearby phones list it under Receive.
 *  - A member asks to join over Bluetooth; the leader allows/denies; on allow the credentials go
 *    back over Bluetooth, the member joins the Wi-Fi Direct group and keeps one TCP connection to the hub.
 *  - Anyone can share files: the leader pushes to every member; a member sends to the leader,
 *    who saves it and relays it to the other members. Everything received lands in Downloads/Heat.
 */
class GroupManager(private val e: HeatEngine) {
    private val ctx = e.app
    private val wifi = WifiDirect(ctx)

    private class Conn(
        val sock: Socket,
        val inp: DataInputStream,
        val out: DataOutputStream,
        val id: String,
        val name: String,
        val flags: Int
    ) {
        val lock = ReentrantLock(true)
        fun close() { runCatching { sock.close() } }
    }

    private class Got(val uri: Uri, val sender: String, val name: String, val size: Long)
    private class Closed : Exception()

    private var job: Job? = null
    @Volatile private var server: ServerSocket? = null
    private val conns = ConcurrentHashMap<String, Conn>()
    @Volatile private var hub: Conn? = null
    @Volatile private var closing = false
    private val approved: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var gid = ""
    private var ssid = ""
    private var pass = ""
    private var wake: PowerManager.WakeLock? = null
    private var joinTimeout: Runnable? = null

    // ------------------------------------------------------------------ small helpers

    fun busy(): Boolean = e.groupUi.value?.live == true

    fun leadingOpen(): Boolean = e.groupUi.value?.let { it.leading && it.phase == GPhase.OPEN } == true

    private fun upd(f: GroupUi.() -> GroupUi) {
        e.groupUi.update { it?.f() }
    }

    private fun addLog(s: String) = upd { copy(log = (log + s).takeLast(40)) }

    private fun json(vararg kv: Pair<String, Any>): String {
        val o = JSONObject().put("id", e.prefs.id)
        kv.forEach { o.put(it.first, it.second) }
        return o.toString()
    }

    private fun fatal(msg: String) {
        upd { copy(phase = GPhase.FAILED, message = msg) }
        e.notifier.failed("Group problem", msg)
    }

    private fun wakeLock(on: Boolean) {
        if (on) {
            if (wake?.isHeld == true) return
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heat:group").also { it.acquire(60 * 60 * 1000L) }
        } else {
            runCatching { wake?.takeIf { it.isHeld }?.release() }
            wake = null
        }
    }

    private fun armJoinTimeout(ms: Long, action: () -> Unit) {
        cancelJoinTimeout()
        val r = Runnable { joinTimeout = null; action() }
        joinTimeout = r
        e.main.postDelayed(r, ms)
    }

    private fun cancelJoinTimeout() {
        joinTimeout?.let { e.main.removeCallbacks(it) }
        joinTimeout = null
    }

    fun targetCount(): Int {
        val cur = e.groupUi.value ?: return 0
        return if (cur.leading) conns.size else if (hub != null) 1 else 0
    }

    fun estimate(bytes: Long, members: Int): Long =
        (4 + bytes * members.coerceAtLeast(1) / e.prefs.throughput).toLong().coerceAtLeast(3)

    // ------------------------------------------------------------------ leader

    fun create() {
        if (busy()) return
        if (e.transfer.value?.phase?.active == true) {
            e.groupUi.value = GroupUi(
                true, e.prefs.id, e.name.value, e.myFlags(), GPhase.FAILED,
                message = "Finish your current transfer first"
            )
            return
        }
        closing = false
        gid = randomId(6)
        ssid = "DIRECT-HG-" + randomId(4, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        pass = randomId(12, "abcdefghjkmnpqrstuvwxyz23456789")
        approved.clear()
        e.groupUi.value = GroupUi(
            true, e.prefs.id, e.name.value, e.myFlags(), GPhase.STARTING, message = "Starting your group…"
        )
        wakeLock(true)
        job = e.scope.launch(Dispatchers.IO) {
            try {
                if (!wifi.wifiOn()) throw Fail("Turn on Wi-Fi to create a group")
                if (!wifi.createGroup(ssid, pass)) {
                    throw Fail("Wi-Fi Direct couldn't start. Keep Wi-Fi (and Location on Android 12) on")
                }
                if (closing) throw Fail("Cancelled")
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(P.GPORT))
                server = s
                upd { copy(phase = GPhase.OPEN, message = null, leaderFlags = e.myFlags() or 8) }
                e.main.post { e.refreshAdvertising() }
                addLog("Group is open. People find it under Receive.")
                while (isActive) {
                    val sock = s.accept()
                    launch { hubClient(sock) }
                }
            } catch (t: Throwable) {
                if (!closing) fatal(if (t is Fail) t.message ?: "Group failed" else "The group stopped")
            } finally {
                teardown()
            }
        }
    }

    /** A nearby phone asked (over Bluetooth) to join the group I lead. */
    fun onJoinRequest(id: String, nm: String, flags: Int) {
        val cur = e.groupUi.value
        if (cur == null || !cur.leading || cur.phase != GPhase.OPEN) {
            e.ble.send(id, P.T_GDENY, json("why" to "That group isn't open any more")) {}
            return
        }
        if (conns.containsKey(id) || cur.pending.any { it.id == id }) return
        if (conns.size + cur.pending.size >= P.GROUP_MAX) {
            e.ble.send(id, P.T_GDENY, json("why" to "The group is full")) {}
            return
        }
        val who = nm.take(16)
        upd { copy(pending = pending + GMember(id, who, flags)) }
        e.notifier.info("$who wants to join your group", "Open Heat to allow or deny")
    }

    fun decide(id: String, allow: Boolean) {
        val cur = e.groupUi.value ?: return
        val m = cur.pending.firstOrNull { it.id == id } ?: return
        upd { copy(pending = pending.filter { it.id != id }) }
        if (allow) {
            approved.add(id)
            e.ble.send(id, P.T_GACCEPT, json("gid" to gid, "ssid" to ssid, "pass" to pass)) {
                approved.remove(id)
                addLog("Couldn't reach ${m.name} over Bluetooth")
            }
        } else {
            e.ble.send(id, P.T_GDENY, json("why" to "The leader said no")) {}
        }
    }

    /** One member's TCP connection to the hub. Runs on an IO thread. */
    private fun hubClient(sock: Socket) {
        var conn: Conn? = null
        try {
            sock.keepAlive = true
            sock.tcpNoDelay = true
            sock.soTimeout = 15_000
            val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), P.BUF))
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), P.BUF))
            if (inp.readInt() != P.MAGIC) throw Fail("bad hello")
            val g = inp.readUTF()
            val id = inp.readUTF()
            val nm = inp.readUTF()
            val fl = inp.readInt()
            if (g != gid || !approved.contains(id)) {
                out.writeInt(0)
                out.flush()
                sock.close()
                return
            }
            out.writeInt(1)
            out.flush()
            sock.soTimeout = 0
            val c = Conn(sock, inp, out, id, nm.take(16), fl)
            conn = c
            conns.put(id, c)?.close()
            addLog("${c.name} joined")
            e.notifier.info("${c.name} joined your group", "Tap to open")
            broadcastMembers()
            while (true) {
                when (c.inp.readInt()) {
                    2 -> {
                        val got = readFile(c, c.name)
                        addLog("Received ${got.name} from ${c.name}")
                        e.notifier.info("File received in your group", "${got.name} from ${c.name}")
                        val others = conns.values.filter { it.id != c.id }
                        if (others.isNotEmpty()) relay(others, c.name, got)
                    }
                    3 -> break
                    else -> throw Fail("bad frame")
                }
            }
        } catch (_: Throwable) {
        } finally {
            val c = conn
            if (c != null) {
                if (conns.remove(c.id, c)) memberLeft(c)
                c.close()
            } else {
                runCatching { sock.close() }
            }
        }
    }

    private fun memberLeft(c: Conn) {
        approved.remove(c.id)
        addLog("${c.name} left")
        broadcastMembers()
    }

    private fun broadcastMembers() {
        val list = conns.values.map { GMember(it.id, it.name, it.flags) }
        upd { copy(members = list) }
        for (c in conns.values.toList()) {
            e.scope.launch(Dispatchers.IO) {
                try {
                    c.lock.lock()
                    try {
                        c.out.writeInt(1)
                        c.out.writeInt(list.size)
                        for (m in list) {
                            c.out.writeUTF(m.id)
                            c.out.writeUTF(m.name)
                            c.out.writeInt(m.flags)
                        }
                        c.out.flush()
                    } finally {
                        c.lock.unlock()
                    }
                } catch (_: Throwable) {
                    c.close()
                }
            }
        }
    }

    private fun relay(targets: List<Conn>, sender: String, got: Got) {
        e.scope.launch(Dispatchers.IO) {
            coroutineScope {
                targets.map { c ->
                    async {
                        try {
                            sendFrame(c, sender, got.name, got.size, got.uri) {}
                        } catch (t: Throwable) {
                            dropConn(c)
                        }
                    }
                }.awaitAll()
            }
            addLog("Shared ${got.name} with ${targets.size} other member(s)")
        }
    }

    private fun dropConn(c: Conn) {
        if (e.groupUi.value?.leading == true) {
            if (conns.remove(c.id, c)) memberLeft(c)
        }
        c.close()   // on a member this makes the read loop end with "lost connection"
    }

    // ------------------------------------------------------------------ member

    fun join(p: Peer) {
        if (busy()) return
        if (e.transfer.value?.phase?.active == true) {
            e.groupUi.value = GroupUi(
                false, p.id, p.name, p.flags, GPhase.FAILED, message = "Finish your current transfer first"
            )
            return
        }
        closing = false
        e.groupUi.value = GroupUi(
            false, p.id, p.name, p.flags, GPhase.JOINING, message = "Waiting for ${p.name} to let you in…"
        )
        e.ble.hold(p.id, true)
        e.ble.send(p.id, P.T_GJOIN, json("name" to e.name.value, "flags" to e.myFlags())) {
            cancelJoinTimeout()
            e.ble.hold(p.id, false)
            fatal("Couldn't reach ${p.name} over Bluetooth")
        }
        armJoinTimeout(60_000) {
            if (e.groupUi.value?.phase == GPhase.JOINING && job == null) {
                e.ble.hold(p.id, false)
                fatal("${p.name} didn't respond")
            }
        }
    }

    fun onAccept(id: String, o: JSONObject) {
        val cur = e.groupUi.value ?: return
        if (cur.leading || cur.phase != GPhase.JOINING || cur.leaderId != id || job != null) return
        cancelJoinTimeout()
        gid = o.optString("gid")
        ssid = o.optString("ssid")
        pass = o.optString("pass")
        upd { copy(message = "Connecting…") }
        wakeLock(true)
        job = e.scope.launch(Dispatchers.IO) { memberFlow(cur) }
    }

    fun onDeny(id: String, o: JSONObject) {
        val cur = e.groupUi.value ?: return
        if (cur.leading || cur.phase != GPhase.JOINING || cur.leaderId != id) return
        cancelJoinTimeout()
        e.ble.hold(id, false)
        fatal(o.optString("why", "The leader said no"))
    }

    private suspend fun memberFlow(start: GroupUi) {
        try {
            if (!wifi.wifiOn()) throw Fail("Turn on Wi-Fi to join a group")
            val host = wifi.join(ssid, pass, 25_000) ?: throw Fail("Couldn't join the group's Wi-Fi Direct link")
            if (closing) throw Fail("Cancelled")
            var s: Socket? = null
            for (i in 0 until 10) {
                if (closing) break
                try {
                    val t = Socket()
                    t.connect(InetSocketAddress(host, P.GPORT), 3000)
                    s = t
                    break
                } catch (x: Throwable) {
                    delay(700)
                }
            }
            val sock = s ?: throw Fail("Couldn't reach the group leader")
            sock.keepAlive = true
            sock.tcpNoDelay = true
            sock.soTimeout = 15_000
            val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), P.BUF))
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), P.BUF))
            out.writeInt(P.MAGIC)
            out.writeUTF(gid)
            out.writeUTF(e.prefs.id)
            out.writeUTF(e.name.value)
            out.writeInt(e.myFlags())
            out.flush()
            if (inp.readInt() != 1) throw Fail("The leader didn't let you in")
            sock.soTimeout = 0
            val c = Conn(sock, inp, out, start.leaderId, start.leaderName, start.leaderFlags)
            hub = c
            e.ble.hold(start.leaderId, false)
            upd { copy(phase = GPhase.CONNECTED, message = null) }
            addLog("Joined ${start.leaderName}'s group")
            while (true) {
                when (inp.readInt()) {
                    1 -> {
                        val n = inp.readInt()
                        val list = ArrayList<GMember>()
                        repeat(n) {
                            val mid = inp.readUTF()
                            val mname = inp.readUTF()
                            val mflags = inp.readInt()
                            list.add(GMember(mid, mname, mflags))
                        }
                        upd { copy(members = list) }
                    }
                    2 -> {
                        val got = readFile(c, null)
                        addLog("Received ${got.name} from ${got.sender}")
                        e.notifier.info("File received in the group", "${got.name} from ${got.sender}")
                    }
                    3 -> throw Closed()
                    else -> throw Fail("bad frame")
                }
            }
        } catch (t: Throwable) {
            if (!closing) {
                if (t is Closed) {
                    upd { copy(phase = GPhase.CLOSED, message = "$leaderName closed the group") }
                    e.notifier.info("Group closed", "${start.leaderName} closed the group")
                } else {
                    fatal(if (t is Fail) t.message ?: "Failed" else "Lost the connection to the leader")
                }
            }
        } finally {
            teardown()
        }
    }

    // ------------------------------------------------------------------ both roles

    /** Leader: closes the group for everyone. Member: leaves. Also clears a finished/failed group screen. */
    fun leave() {
        val cur = e.groupUi.value ?: return
        if (!cur.live) {
            e.groupUi.value = null
            return
        }
        cancelJoinTimeout()
        if (job == null) {            // still waiting to be let in: nothing is running yet
            e.ble.hold(cur.leaderId, false)
            e.groupUi.value = null
            return
        }
        closing = true
        upd { copy(message = if (leading) "Closing the group…" else "Leaving…") }
        e.scope.launch(Dispatchers.IO) {
            val all = if (cur.leading) conns.values.toList() else listOfNotNull(hub)
            for (c in all) {
                try {
                    if (c.lock.tryLock(300, TimeUnit.MILLISECONDS)) {
                        try {
                            c.out.writeInt(3)
                            c.out.flush()
                        } finally {
                            c.lock.unlock()
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            runCatching { server?.close() }
            all.forEach { it.close() }
            job?.cancel()
        }
    }

    private suspend fun teardown() {
        runCatching { server?.close() }
        server = null
        conns.values.toList().forEach { it.close() }
        conns.clear()
        hub?.close()
        hub = null
        approved.clear()
        e.groupUi.value?.leaderId?.let { e.ble.hold(it, false) }
        withContext(NonCancellable) { wifi.clear() }
        wakeLock(false)
        job = null
        if (closing) {
            e.groupUi.value = null
            closing = false
        }
        e.main.post { e.refreshAdvertising() }
    }

    private fun readFile(c: Conn, senderOverride: String?): Got {
        val inp = c.inp
        val sender = inp.readUTF()
        val name = Sink.clean(inp.readUTF())
        val size = inp.readLong()
        if (size < 0) throw Fail("bad size")
        c.sock.soTimeout = 30_000
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val (uri, os) = Sink.open(ctx, "${stamp}_$name", Sink.mimeOf(name))
        try {
            val buf = ByteArray(P.BUF)
            var left = size
            while (left > 0) {
                val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw Fail("Connection closed early")
                os.write(buf, 0, n)
                left -= n
            }
            os.flush()
            os.close()
            Sink.finish(ctx, uri)
        } catch (t: Throwable) {
            runCatching { os.close() }
            Sink.abort(ctx, uri)
            throw t
        }
        c.sock.soTimeout = 0
        return Got(uri, senderOverride ?: sender, name, size)
    }

    private fun sendFrame(c: Conn, sender: String, name: String, size: Long, uri: Uri, onBytes: (Int) -> Unit) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw Fail("Couldn't open $name")
        input.use { ins ->
            c.lock.lock()
            try {
                c.out.writeInt(2)
                c.out.writeUTF(sender)
                c.out.writeUTF(name)
                c.out.writeLong(size)
                val buf = ByteArray(P.BUF)
                var left = size
                while (left > 0) {
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw Fail("$name changed while sending")
                    c.out.write(buf, 0, n)
                    left -= n
                    onBytes(n)
                }
                c.out.flush()
            } finally {
                c.lock.unlock()
            }
        }
    }

    /** Send files to everyone in the group (leader) or to the leader, who relays them (member). */
    fun share(picked: List<Uri>) {
        val cur = e.groupUi.value ?: return
        if (cur.phase != GPhase.OPEN && cur.phase != GPhase.CONNECTED) return
        if (cur.share?.finished == false) return
        e.scope.launch(Dispatchers.IO) {
            val targets: List<Conn> = if (cur.leading) conns.values.toList() else listOfNotNull(hub)
            if (targets.isEmpty()) {
                addLog("Nobody to send to yet")
                return@launch
            }
            picked.forEach { u ->
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val info = e.transfers.describe(picked)
            if (info.isEmpty()) return@launch
            val perMember = info.sumOf { it.second }
            val total = perMember * targets.size
            val label = if (info.size > 1) "${info[0].first} +${info.size - 1} more" else info[0].first
            upd { copy(share = GShare(label, total, 0L, 0.0, -1L, estimate(perMember, targets.size), false)) }

            val done = AtomicLong(0)
            val lastEmit = AtomicLong(0)
            val t0 = SystemClock.elapsedRealtime()
            val onBytes: (Int) -> Unit = { n ->
                val d = done.addAndGet(n.toLong())
                val t = SystemClock.elapsedRealtime()
                if (t - lastEmit.get() >= 300) {
                    lastEmit.set(t)
                    val secs = (t - t0) / 1000.0
                    val sp = if (secs > 0.4) d / secs else 0.0
                    val eta = if (sp > 1) ((total - d) / sp).toLong() else -1L
                    upd { copy(share = share?.copy(done = d, speedBps = sp, etaSec = eta)) }
                }
            }

            var failed = 0
            for (i in picked.indices) {
                val name = info[i].first
                val size = info[i].second
                val results = coroutineScope {
                    targets.map { c ->
                        async {
                            try {
                                sendFrame(c, e.name.value, name, size, picked[i], onBytes)
                                true
                            } catch (t: Throwable) {
                                dropConn(c)
                                false
                            }
                        }
                    }.awaitAll()
                }
                failed += results.count { !it }
            }

            val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
            val d = done.get()
            if (secs > 1.5 && d > 512 * 1024) {
                e.prefs.throughput = (0.5 * e.prefs.throughput + 0.5 * (d / secs)).coerceIn(1e6, 5e7)
            }
            val ok = failed == 0
            val note = if (ok) "Sent to ${targets.size} member(s)" else "Some members didn't get everything"
            upd { copy(share = share?.copy(done = if (ok) total else d, etaSec = 0, finished = true, note = note)) }
            addLog("$label: $note")
            if (ok) e.notifier.done("Group transfer successful", "$label sent to ${targets.size} member(s)")
            else e.notifier.failed("Group transfer incomplete", "$label: $note")
        }
    }
}
