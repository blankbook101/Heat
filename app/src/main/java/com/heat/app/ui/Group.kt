package com.heat.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.heat.app.core.GMember
import com.heat.app.core.GPhase
import com.heat.app.core.GShare
import com.heat.app.core.HeatEngine
import com.heat.app.core.Peer
import com.heat.app.core.fmtBytes
import com.heat.app.core.fmtDur
import com.heat.app.core.fmtSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Card shown on Home/Receive while a group exists; tap to open the group room. */
@Composable
fun GroupBanner(engine: HeatEngine, onOpen: () -> Unit) {
    val ui by engine.groupUi.collectAsState()
    val g = ui ?: return
    val cs = MaterialTheme.colorScheme
    val text = when (g.phase) {
        GPhase.STARTING -> "Starting your group…"
        GPhase.OPEN -> "You lead a group · ${g.members.size} member(s)" +
            (if (g.pending.isNotEmpty()) " · ${g.pending.size} waiting" else "")
        GPhase.JOINING -> "Joining ${g.leaderName}'s group…"
        GPhase.CONNECTED -> "In ${g.leaderName}'s group"
        GPhase.CLOSED -> "Group closed"
        GPhase.FAILED -> "Group problem"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.primaryContainer)
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, Modifier.weight(1f), color = cs.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
        Text("Open", color = cs.onPrimaryContainer)
    }
}

/** A nearby group leader, listed on the Receive screen under the single-sender area. */
@Composable
fun GroupRow(p: Peer, avatar: ImageBitmap?, onJoin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceVariant)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(p.name, p.flags and 4 != 0, avatar, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            NameText(p.name, p.gold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Open group", style = MaterialTheme.typography.bodySmall, color = cs.primary)
        }
        Button(onClick = onJoin) { Text("Join") }
    }
}

@Composable
private fun MemberRow(name: String, gold: Boolean, egg: Boolean, bmp: ImageBitmap?, tag: String?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name, egg, bmp, 40.dp)
        Spacer(Modifier.width(12.dp))
        NameText(name, gold, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (tag != null) Text(tag, style = MaterialTheme.typography.labelMedium, color = cs.primary)
    }
}

@Composable
private fun ShareCard(s: GShare) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cs.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            if (s.finished) "Group transfer" else "Sending to the group",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold
        )
        Text(
            "${s.label} · ${fmtBytes(s.total)} in total",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        if (s.finished) {
            Text(s.note ?: "Done", style = MaterialTheme.typography.bodyMedium)
        } else {
            val frac = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${(frac * 100).toInt()}% · ${fmtBytes(s.done)}", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (s.etaSec >= 0) "${fmtSpeed(s.speedBps)} · ~${fmtDur(s.etaSec)} left"
                    else "estimating… ~${fmtDur(s.estimateSec)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun GroupConfirm(engine: HeatEngine, uris: List<Uri>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val info by produceState<List<Pair<String, Long>>?>(null, uris) {
        value = withContext(Dispatchers.IO) { engine.transfers.describe(uris) }
    }
    val n = engine.groups.targetCount()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to the group?") },
        text = {
            val list = info
            if (list == null) {
                Text("Reading files…")
            } else {
                val total = list.sumOf { it.second }
                Column {
                    list.take(4).forEach {
                        Text("${it.first} · ${fmtBytes(it.second)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (list.size > 4) Text("+${list.size - 4} more", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text("Total ${fmtBytes(total)} to $n member(s)", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Estimated time: about ${fmtDur(engine.groups.estimate(total, n))}",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = { Button(enabled = info != null && n > 0, onClick = onConfirm) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ===================================================================== group room

@Composable
fun GroupScreen(engine: HeatEngine, onBack: () -> Unit) {
    val ui by engine.groupUi.collectAsState()
    val avatars by engine.peerAvatars.collectAsState()
    val myPhoto by engine.myAvatar.collectAsState()
    val myMode by engine.avatarMode.collectAsState()
    val myEgg by engine.eggFound.collectAsState()
    val myName by engine.name.collectAsState()
    val g = ui
    LaunchedEffect(g == null) { if (g == null) onBack() }
    if (g == null) return

    val cs = MaterialTheme.colorScheme
    val myId = engine.prefs.id
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var confirmLeave by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked = it }

    val dead = g.phase == GPhase.CLOSED || g.phase == GPhase.FAILED
    val sh = g.share
    val canSend = (g.phase == GPhase.OPEN || g.phase == GPhase.CONNECTED) &&
        (sh == null || sh.finished) &&
        (!g.leading || g.members.isNotEmpty())

    Column(Modifier.fillMaxSize()) {
        HeatTopBar(
            if (g.leading) "Your group" else g.leaderName,
            onBack,
            when (g.phase) {
                GPhase.STARTING -> "Starting…"
                GPhase.OPEN -> "You are the leader · up to 6 members"
                GPhase.JOINING -> "Joining…"
                GPhase.CONNECTED -> "Connected · leader: ${g.leaderName}"
                GPhase.CLOSED -> "Closed"
                GPhase.FAILED -> "Problem"
            },
            gold = !g.leading && (g.leaderFlags and 1 != 0)
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (g.message != null) {
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer).padding(14.dp)) {
                        Text(g.message, color = cs.onPrimaryContainer)
                        if (g.phase == GPhase.STARTING || g.phase == GPhase.JOINING) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if (g.leading && g.pending.isNotEmpty()) {
                item { Text("Wants to join", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                items(g.pending, key = { "p" + it.id }) { m ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceVariant).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NameText(m.name, m.gold, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { engine.groups.decide(m.id, false) }) { Text("Deny") }
                        Button(onClick = { engine.groups.decide(m.id, true) }) { Text("Allow") }
                    }
                }
            }
            item { Text("Members", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            item {
                if (g.leading) {
                    MemberRow(myName, myEgg, myMode == 2 && myEgg, if (myMode == 1) myPhoto else null, "Leader · You")
                } else {
                    MemberRow(g.leaderName, g.leaderFlags and 1 != 0, g.leaderFlags and 4 != 0, avatars[g.leaderId], "Leader")
                }
            }
            items(g.members, key = { "m" + it.id }) { m: GMember ->
                val me = m.id == myId
                MemberRow(
                    if (me) myName else m.name,
                    if (me) myEgg else m.gold,
                    if (me) myMode == 2 && myEgg else m.egg,
                    if (me) (if (myMode == 1) myPhoto else null) else avatars[m.id],
                    if (me) "You" else null
                )
            }
            if (g.leading && g.members.isEmpty() && g.phase == GPhase.OPEN) {
                item {
                    Text(
                        "No members yet. Ask people to open Receive on their phone and join your group.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                }
            }
            if (sh != null) item { ShareCard(sh) }
            if (g.log.isNotEmpty()) {
                item { Text("Activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                items(g.log.reversed().take(12)) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        if (dead) {
            Button(onClick = { engine.groups.leave() }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("OK") }
        } else {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = canSend, modifier = Modifier.fillMaxWidth()) {
                    Text("Send files to the group")
                }
                OutlinedButton(
                    onClick = { confirmLeave = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error)
                ) { Text(if (g.leading) "Close group" else "Leave group") }
            }
        }
    }

    if (picked.isNotEmpty()) {
        GroupConfirm(
            engine, picked,
            onConfirm = { engine.groups.share(picked); picked = emptyList() },
            onDismiss = { picked = emptyList() }
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (g.leading) "Close the group?" else "Leave the group?") },
            text = { Text(if (g.leading) "Everyone will be disconnected." else "You will stop receiving the group's files.") },
            confirmButton = {
                Button(
                    onClick = { confirmLeave = false; engine.groups.leave() },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)
                ) { Text(if (g.leading) "Close" else "Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } }
        )
    }
}
