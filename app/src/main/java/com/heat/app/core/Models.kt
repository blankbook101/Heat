package com.heat.app.core

import android.bluetooth.BluetoothDevice

data class Peer(
    val id: String,
    val name: String,
    val device: BluetoothDevice?,
    val level: Int,          // 0..3 signal bars
    val firstSeen: Long,
    val flags: Int = 0,      // bit0 egg found, bit1 has photo, bit2 secret pfp, bit3 leading a group
    val ver: Int = 0         // changes whenever the profile photo changes
) {
    val gold: Boolean get() = flags and 1 != 0
    val leadsGroup: Boolean get() = flags and 8 != 0
}

/** kind: 0 = me, 1 = them, 2 = system line */
data class ChatMsg(val kind: Int, val text: String, val ts: Long)

enum class Phase { WAITING, CONNECTING, TRANSFERRING, DONE, FAILED, DECLINED, CANCELLED }

val Phase.active: Boolean
    get() = this == Phase.WAITING || this == Phase.CONNECTING || this == Phase.TRANSFERRING

data class TransferUi(
    val sid: String,
    val peerId: String,
    val peerName: String,
    val outgoing: Boolean,
    val label: String,
    val count: Int,
    val totalBytes: Long,
    val phase: Phase,
    val doneBytes: Long = 0,
    val speedBps: Double = 0.0,
    val etaSec: Long = -1,
    val estimateSec: Long = 0,
    val message: String? = null
)
