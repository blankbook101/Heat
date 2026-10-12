package com.heat.app.core

import java.util.Locale

fun fmtBytes(b: Long): String {
    val v = b.toDouble()
    return when {
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> String.format(Locale.US, "%.0f KB", v / 1024)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", v / 1048576)
        else -> String.format(Locale.US, "%.2f GB", v / 1073741824)
    }
}

fun fmtDur(sec: Long): String = when {
    sec < 0 -> "…"
    sec < 60 -> "$sec s"
    sec < 3600 -> "${sec / 60} min ${sec % 60} s"
    else -> "${sec / 3600} h ${(sec % 3600) / 60} min"
}

fun fmtSpeed(bps: Double): String = String.format(Locale.US, "%.1f MB/s", bps / 1048576)

fun hexToBytes(h: String): ByteArray =
    ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun bytesToHex(b: ByteArray, off: Int, len: Int): String = buildString {
    for (i in off until off + len) append(String.format(Locale.US, "%02x", b[i]))
}

/** UTF-8 bytes of [s], cut (on a character boundary) to at most [max] bytes. */
fun trimUtf8(s: String, max: Int): ByteArray {
    var t = s
    while (t.isNotEmpty() && t.toByteArray(Charsets.UTF_8).size > max) t = t.dropLast(1)
    return t.toByteArray(Charsets.UTF_8)
}

fun randomId(n: Int, alphabet: String = "0123456789abcdef"): String =
    (1..n).map { alphabet.random() }.joinToString("")
