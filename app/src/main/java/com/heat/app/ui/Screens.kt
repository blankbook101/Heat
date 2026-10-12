package com.heat.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heat.app.WebHeatActivity
import com.heat.app.core.ChatMsg
import com.heat.app.core.HeatEngine
import com.heat.app.core.Peer
import com.heat.app.core.Phase
import com.heat.app.core.TransferUi
import com.heat.app.core.active
import com.heat.app.core.fmtBytes
import com.heat.app.core.fmtDur
import com.heat.app.core.fmtSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime

sealed interface Screen {
    data object Home : Screen
    data object Discover : Screen
    data object Receive : Screen
    data object Burn : Screen
    data object Profile : Screen
    data object Group : Screen
    data class Chat(val id: String) : Screen
}

// ===================================================================== root

@Composable
fun HeatRoot(
    engine: HeatEngine,
    permsGranted: Boolean,
    onRequestPerms: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnableBt: () -> Unit
) {
    val mode by engine.themeMode.collectAsState()
    val termsOk by engine.termsOk.collectAsState()
    HeatTheme(mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.systemBarsPadding()) {
                if (!termsOk) TermsScreen(onAccept = { engine.acceptTerms(); onRequestPerms() })
                else if (!permsGranted) PermissionScreen(onRequestPerms, onOpenSettings)
                else AppNav(engine, onEnableBt)
            }
        }
    }
}

@Composable
private fun AppNav(engine: HeatEngine, onEnableBt: () -> Unit) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val openPeer by engine.openPeer.collectAsState()
    val transfer by engine.transfer.collectAsState()

    LaunchedEffect(openPeer) {
        openPeer?.let {
            screen = Screen.Chat(it)
            engine.openPeer.value = null
        }
    }
    BackHandler(enabled = screen != Screen.Home) {
        screen = if (screen is Screen.Chat) Screen.Discover else Screen.Home
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            engine, onEnableBt,
            onSend = { screen = Screen.Discover },
            onReceive = { screen = Screen.Receive },
            onBurn = { screen = Screen.Burn },
            onProfile = { screen = Screen.Profile },
            onGroup = { screen = Screen.Group },
            onWeb = { ctx.startActivity(Intent(ctx, WebHeatActivity::class.java)) }
        )
        Screen.Burn -> BurnScreen(engine, onBack = { screen = Screen.Home })
        Screen.Profile -> ProfileScreen(engine, onBack = { screen = Screen.Home })
        Screen.Group -> GroupScreen(engine, onBack = { screen = Screen.Home })
        Screen.Discover -> DiscoverScreen(
            engine,
            onBack = { screen = Screen.Home },
            onOpen = { screen = Screen.Chat(it) },
            onCreateGroup = { engine.groups.create(); screen = Screen.Group },
            onOpenGroup = { screen = Screen.Group }
        )
        Screen.Receive -> ReceiveScreen(
            engine,
            onBack = { screen = Screen.Home },
            onJoin = { engine.groups.join(it); screen = Screen.Group },
            onOpenGroup = { screen = Screen.Group }
        )
        is Screen.Chat -> ChatScreen(engine, s.id, onBack = { screen = Screen.Discover })
    }

    val t = transfer
    if (t != null && !t.outgoing && t.phase == Phase.WAITING) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${t.peerName} wants to send you ${if (t.count > 1) "${t.count} files" else "a file"}") },
            text = {
                Column {
                    Text(t.label, fontWeight = FontWeight.SemiBold)
                    Text("${fmtBytes(t.totalBytes)} · about ${fmtDur(t.estimateSec)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Saved in Downloads/Heat with the date and time.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = { engine.transfers.acceptIncoming() }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { engine.transfers.declineIncoming() }) { Text("Decline") } }
        )
    }
}

// ===================================================================== shared bits

@Composable
internal fun HeatTopBar(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    gold: Boolean = false,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 12.dp else 0.dp)) {
            NameText(title, gold, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

@Composable
private fun NameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name") },
        text = {
            Column {
                Text("Nearby people see this name while scanning.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 16) text = it },
                    singleLine = true,
                    supportingText = { Text("${text.length}/16") }
                )
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NameChip(engine: HeatEngine) {
    val name by engine.name.collectAsState()
    val egg by engine.eggFound.collectAsState()
    var edit by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { edit = true }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Visible as ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NameText(name, egg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.Edit, contentDescription = "Edit name", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
    if (edit) NameDialog(name, onSave = { engine.setName(it) }, onDismiss = { edit = false })
}

@Composable
private fun greeting(): String {
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            hour = LocalTime.now().hour
        }
    }
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..20 -> "Good evening"
        else -> "Hello, night owl"
    }
}

@Composable
private fun TransferSection(engine: HeatEngine) {
    val t by engine.transfer.collectAsState()
    val ui = t ?: return
    if (!ui.outgoing && ui.phase == Phase.WAITING) return   // handled by the accept dialog
    LaunchedEffect(ui.sid, ui.phase) {
        if (ui.phase == Phase.DONE) {
            delay(9000)
            engine.transfers.dismiss()
        }
    }
    TransferCard(ui, onCancel = { engine.transfers.cancel() }, onDismiss = { engine.transfers.dismiss() })
}

@Composable
private fun TransferCard(ui: TransferUi, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val title = when (ui.phase) {
        Phase.DONE -> if (ui.outgoing) "Sent" else "Received"
        Phase.FAILED -> "Transfer failed"
        Phase.DECLINED -> "Declined"
        Phase.CANCELLED -> "Cancelled"
        else -> if (ui.outgoing) "Sending to ${ui.peerName}" else "Receiving from ${ui.peerName}"
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
        border = BorderStroke(1.dp, Brush.linearGradient(auroraColors()))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${ui.label} · ${fmtBytes(ui.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            when (ui.phase) {
                Phase.WAITING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("Waiting for ${ui.peerName} to accept · estimated ${fmtDur(ui.estimateSec)}", style = MaterialTheme.typography.bodySmall)
                }
                Phase.CONNECTING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("Setting up the Wi-Fi Direct link · estimated ${fmtDur(ui.estimateSec)} in total", style = MaterialTheme.typography.bodySmall)
                }
                Phase.TRANSFERRING -> {
                    val frac = if (ui.totalBytes > 0) (ui.doneBytes.toFloat() / ui.totalBytes).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${(frac * 100).toInt()}% · ${fmtBytes(ui.doneBytes)}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (ui.etaSec >= 0) "${fmtSpeed(ui.speedBps)} · ~${fmtDur(ui.etaSec)} left"
                            else "estimating… ~${fmtDur(ui.estimateSec)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                else -> Text(ui.message ?: "", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (ui.phase.active) TextButton(onClick = onCancel) { Text("Cancel") }
                else TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

// ===================================================================== home

@Composable
private fun HomeScreen(
    engine: HeatEngine,
    onEnableBt: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onBurn: () -> Unit,
    onProfile: () -> Unit,
    onGroup: () -> Unit,
    onWeb: () -> Unit
) {
    val name by engine.name.collectAsState()
    val mode by engine.themeMode.collectAsState()
    val btOn by engine.btOn.collectAsState()
    val egg by engine.eggFound.collectAsState()
    val avMode by engine.avatarMode.collectAsState()
    val photo by engine.myAvatar.collectAsState()
    var showTerms by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Heat",
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onBurn).padding(horizontal = 8.dp, vertical = 4.dp),
                style = TextStyle(brush = Brush.linearGradient(auroraColors()), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { engine.cycleTheme() }) {
                Text(
                    when (mode) { 1 -> "☀ Light"; 2 -> "☾ Dark"; else -> "◐ Auto" },
                    color = cs.onSurfaceVariant
                )
            }
        }
        TransferSection(engine)
        GroupBanner(engine, onGroup)
        if (!btOn) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bluetooth is off", Modifier.weight(1f), color = cs.onPrimaryContainer)
                    Button(onClick = onEnableBt) { Text("Turn on") }
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onProfile).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(name, egg && avMode == 2, if (avMode == 1) photo else null, 64.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(greeting(), style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                    NameText(name, egg, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Default.Edit, contentDescription = "Edit profile", tint = cs.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(36.dp))
            Text("What would you like to do?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActionCard("Send", HIcons.Upload, Modifier.weight(1f), onSend)
                ActionCard("Receive", HIcons.Download, Modifier.weight(1f), onReceive)
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onWeb, modifier = Modifier.fillMaxWidth()) { Text("Connect with iPhone or web (QR)") }
        }
        Text(
            "Bluetooth finds people · Wi-Fi Direct moves files",
            Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
        TextButton(onClick = { showTerms = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Terms of Use", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showTerms) {
        AlertDialog(
            onDismissRequest = { showTerms = false },
            title = { Text("Terms of Use") },
            text = { TermsText(Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showTerms = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun ActionCard(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .height(150.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(cs.surfaceVariant)
            .border(1.dp, Brush.linearGradient(auroraColors()), RoundedCornerShape(26.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Brush.linearGradient(auroraColors())),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = cs.background, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

// ===================================================================== discover (send)

@Composable
private fun DiscoverScreen(
    engine: HeatEngine,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onOpenGroup: () -> Unit
) {
    val peers by engine.peers.collectAsState()
    val unread by engine.unread.collectAsState()
    val avatars by engine.peerAvatars.collectAsState()
    val group by engine.groupUi.collectAsState()
    LaunchedEffect(peers) { peers.forEach { engine.wantAvatar(it) } }
    DisposableEffect(Unit) {
        engine.startDiscovery()
        engine.setAdvBoost(true)
        onDispose {
            engine.stopDiscovery()
            engine.setAdvBoost(false)
        }
    }
    Column(Modifier.fillMaxSize()) {
        HeatTopBar("Nearby", onBack, "Tap a name to chat or send files")
        NameChip(engine)
        if (group == null) {
            OutlinedButton(onClick = onCreateGroup, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Create a group")
            }
        } else {
            GroupBanner(engine, onOpenGroup)
        }
        TransferSection(engine)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (peers.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    WolfLoader()
                    Text("Looking for people nearby…", style = MaterialTheme.typography.titleMedium)
                    Text("Ask them to open Heat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(peers, key = { it.id }) { p ->
                        DropIn(Modifier.animateItem()) { PeerRow(p, unread[p.id] ?: 0, avatars[p.id]) { onOpen(p.id) } }
                    }
                }
            }
        }
    }
}

/** Slides the content down from above the list when it first appears. */
@Composable
private fun DropIn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown,
        modifier = modifier,
        enter = slideInVertically(tween(480, easing = FastOutSlowInEasing)) { -it * 2 } + fadeIn(tween(480))
    ) { content() }
}

@Composable
private fun PeerRow(p: Peer, unread: Int, avatar: ImageBitmap?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(p.name, p.flags and 4 != 0, avatar, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            NameText(p.name, p.gold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("●".repeat(p.level + 1) + "○".repeat(3 - p.level), style = MaterialTheme.typography.bodySmall, color = cs.primary)
        }
        if (unread > 0) {
            Box(Modifier.clip(CircleShape).background(cs.tertiary).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("$unread", color = cs.background, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ===================================================================== receive

@Composable
private fun ReceiveScreen(engine: HeatEngine, onBack: () -> Unit, onJoin: (Peer) -> Unit, onOpenGroup: () -> Unit) {
    val name by engine.name.collectAsState()
    val peers by engine.peers.collectAsState()
    val avatars by engine.peerAvatars.collectAsState()
    val groups = peers.filter { it.leadsGroup }
    DisposableEffect(Unit) {
        // scanning here is what lets us list nearby groups
        engine.startDiscovery()
        engine.setAdvBoost(true)
        onDispose {
            engine.stopDiscovery()
            engine.setAdvBoost(false)
        }
    }
    LaunchedEffect(groups) { groups.forEach { engine.wantAvatar(it) } }
    Column(Modifier.fillMaxSize()) {
        HeatTopBar("Receive", onBack, "Ready for incoming files")
        NameChip(engine)
        TransferSection(engine)
        GroupBanner(engine, onOpenGroup)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    WolfLoader(Modifier.size(180.dp))
                    Text("Waiting for a sender…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Ask them to pick “$name” in their Send list",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
            item {
                Text("Groups nearby", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (groups.isEmpty()) {
                item {
                    Text(
                        "No groups nearby. A group leader's name shows up here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(groups, key = { it.id }) { p ->
                GroupRow(p, avatars[p.id]) { onJoin(p) }
            }
        }
    }
}

// ===================================================================== chat + send files

@Composable
private fun ChatScreen(engine: HeatEngine, peerId: String, onBack: () -> Unit) {
    val chats by engine.chats.collectAsState()
    val msgs = chats[peerId] ?: emptyList()
    val peerName = engine.names[peerId] ?: "Heat user"
    var text by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }

    DisposableEffect(peerId) {
        engine.enterChat(peerId)
        onDispose { engine.leaveChat() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked = it }

    Column(Modifier.fillMaxSize().imePadding()) {
        HeatTopBar(
            peerName, onBack, "Chat over Bluetooth · files over Wi-Fi Direct",
            gold = ((engine.peerFlags[peerId] ?: 0) and 1) != 0
        )
        TransferSection(engine)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(msgs.reversed()) { Bubble(it) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Icon(HIcons.Attach, contentDescription = "Send files", tint = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 500) text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(),
                keyboardActions = KeyboardActions()
            )
            IconButton(
                enabled = text.isNotBlank(),
                onClick = { engine.sendMessage(peerId, text); text = "" }
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary) }
        }
    }

    if (picked.isNotEmpty()) {
        ConfirmSend(engine, peerName, picked,
            onConfirm = { engine.transfers.startSend(peerId, peerName, picked); picked = emptyList() },
            onDismiss = { picked = emptyList() })
    }
}

@Composable
private fun ConfirmSend(engine: HeatEngine, peerName: String, uris: List<android.net.Uri>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val info by produceState<List<Pair<String, Long>>?>(null, uris) {
        value = withContext(Dispatchers.IO) { engine.transfers.describe(uris) }
    }
    val busy = engine.transfer.collectAsState().value?.phase?.active == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to $peerName?") },
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
                    Text("Total ${fmtBytes(total)}", fontWeight = FontWeight.SemiBold)
                    Text("Estimated time: about ${fmtDur(engine.transfers.estimate(total))}", color = MaterialTheme.colorScheme.primary)
                    if (busy) Text("Another transfer is in progress.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { Button(enabled = info != null && !busy, onClick = onConfirm) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Bubble(m: ChatMsg) {
    val cs = MaterialTheme.colorScheme
    when (m.kind) {
        2 -> Text(
            m.text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
        )
        else -> {
            val mine = m.kind == 0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                Box(
                    Modifier
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp))
                        .background(if (mine) cs.primary else cs.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(m.text, color = if (mine) cs.onPrimary else cs.onSurface)
                }
            }
        }
    }
}

// ===================================================================== permissions

@Composable
private fun PermissionScreen(onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        WolfLoader(Modifier.size(200.dp))
        Spacer(Modifier.height(12.dp))
        Text("Heat needs a few permissions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Nearby devices (Bluetooth and Wi-Fi) to find people and move files, and notifications for messages and transfers. Nothing goes to the internet.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("Grant permissions") }
        TextButton(onClick = onSettings) { Text("Open app settings") }
    }
}
