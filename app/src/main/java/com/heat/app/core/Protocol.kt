package com.heat.app.core

import java.io.ByteArrayOutputStream
import java.util.UUID

object P {
    val SERVICE: UUID = UUID.fromString("7e1a0000-4845-4154-8000-00805f9b34fb")
    val INBOX: UUID = UUID.fromString("7e1a0001-4845-4154-8000-00805f9b34fb")   // peers write here
    val OUTBOX: UUID = UUID.fromString("7e1a0002-4845-4154-8000-00805f9b34fb")  // we notify from here
    val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val COMPANY_ID = 0xFFFF

    const val T_MSG = 2
    const val T_OFFER = 3
    const val T_ACCEPT = 4
    const val T_DECLINE = 5
    const val T_CANCEL = 6
    const val T_AVREQ = 7     // "send me your profile picture"
    const val T_AV = 8        // profile picture (base64 JPEG)
    const val T_GJOIN = 9     // ask a group leader to let me in
    const val T_GACCEPT = 10  // leader -> member: Wi-Fi Direct credentials
    const val T_GDENY = 11

    const val GPORT = 8989
    const val GROUP_MAX = 6

    const val TCP_PORT = 8988
    const val MAGIC = 0x48454154 // "HEAT"
    const val BUF = 64 * 1024
}

/** Splits one logical frame into BLE-sized chunks: [type][more][payload...]. */
object Framing {
    fun chunks(type: Int, json: String, maxChunk: Int): List<ByteArray> {
        val body = json.toByteArray(Charsets.UTF_8)
        val room = (maxChunk - 2).coerceAtLeast(10)
        val out = ArrayList<ByteArray>()
        var off = 0
        do {
            val end = minOf(body.size, off + room)
            val more = end < body.size
            val c = ByteArray(2 + end - off)
            c[0] = type.toByte()
            c[1] = if (more) 1 else 0
            System.arraycopy(body, off, c, 2, end - off)
            out.add(c)
            off = end
        } while (off < body.size)
        return out
    }
}

class Reassembler {
    private val buf = ByteArrayOutputStream()
    private var type = -1

    /** Returns (type, json) once the last chunk of a frame arrives. */
    fun feed(chunk: ByteArray): Pair<Int, String>? {
        if (chunk.size < 2) return null
        if (buf.size() == 0) type = chunk[0].toInt()
        buf.write(chunk, 2, chunk.size - 2)
        if (chunk[1].toInt() == 0) {
            val s = buf.toString("UTF-8")
            buf.reset()
            return type to s
        }
        if (buf.size() > 16_384) buf.reset()
        return null
    }
}
