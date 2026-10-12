package com.heat.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.heat.app.core.HeatEngine
import com.heat.app.core.HeatFile
import com.heat.app.core.fmtBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

private val BurnOrange = Color(0xFFFF5A1F)

// ===================================================================== flame

/** A flickering flame drawn on a Canvas: a few paths and some embers, one float animation. */
@Composable
fun FlameFlicker(modifier: Modifier = Modifier.size(120.dp)) {
    val inf = rememberInfiniteTransition(label = "flame")
    val t by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "t"
    )
    Canvas(modifier) {
        val side = size.minDimension
        val s = side / 100f
        withTransform({
            translate((size.width - side) / 2f, (size.height - side) / 2f)
            scale(s, s, Offset.Zero)
        }) { drawFlame(t) }
    }
}

private fun DrawScope.flameShape(cx: Float, base: Float, w: Float, h: Float, sway: Float, brush: Brush) {
    val tip = base - h
    val p = Path().apply {
        moveTo(cx + sway, tip)
        cubicTo(cx + w * 0.12f + sway * 0.5f, base - h * 0.62f, cx + w * 0.52f, base - h * 0.38f, cx + w * 0.5f, base - h * 0.18f)
        cubicTo(cx + w * 0.48f, base + h * 0.02f, cx - w * 0.48f, base + h * 0.02f, cx - w * 0.5f, base - h * 0.18f)
        cubicTo(cx - w * 0.52f, base - h * 0.38f, cx - w * 0.12f + sway * 0.5f, base - h * 0.62f, cx + sway, tip)
        close()
    }
    drawPath(p, brush)
}

private fun DrawScope.drawFlame(t: Float) {
    val tau = (2 * PI).toFloat()
    val a = sin(tau * t)
    val b = sin(tau * t * 2f + 1.3f)
    val c = sin(tau * t * 3f + 2.1f)

    // warm glow behind the fire
    drawCircle(
        Brush.radialGradient(listOf(Color(0x66FF7A18), Color.Transparent), center = Offset(50f, 66f), radius = 52f),
        radius = 52f, center = Offset(50f, 66f)
    )

    val outer = Brush.verticalGradient(listOf(Color(0xFFE53000), Color(0xFFFF8A00)), startY = 12f, endY = 92f)
    val mid = Brush.verticalGradient(listOf(Color(0xFFFF8A00), Color(0xFFFFB300)), startY = 28f, endY = 92f)
    val inner = Brush.verticalGradient(listOf(Color(0xFFFFC107), Color(0xFFFFE57F)), startY = 46f, endY = 92f)
    val core = Brush.verticalGradient(listOf(Color(0xFFFFF3C4), Color(0xFFFFFFFF)), startY = 62f, endY = 92f)

    flameShape(30f, 92f, 24f, 38f + 6f * c, -3f * b, outer)
    flameShape(70f, 92f, 24f, 34f - 6f * c, 3f * a, outer)
    flameShape(50f, 92f, 58f, 80f + 5f * b, 5f * a, outer)
    flameShape(50f, 92f, 42f, 60f + 4f * c, 3f * b, mid)
    flameShape(50f, 92f, 26f, 40f + 3f * a, 2f * c, inner)
    flameShape(50f, 92f, 12f, 20f + 2f * b, 1f * a, core)

    // rising embers
    for (i in 0 until 7) {
        val ph = (t + i * 0.143f) % 1f
        val x = 50f + sin(tau * (ph * 1.5f + i * 0.37f)) * (10f + i * 2.5f)
        val y = 70f - ph * 62f
        val al = (sin(ph * PI.toFloat()) * 0.9f).coerceIn(0f, 1f)
        drawCircle(Color(0xFFFFC857), radius = 0.9f + (i % 3) * 0.5f, center = Offset(x, y), alpha = al)
    }
}

// ===================================================================== screen

private val STAMP = Regex("^(\\d{4}-\\d{2}-\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})_(.+)$")

private fun prettyName(f: HeatFile): Pair<String, String> {
    val m = STAMP.matchEntire(f.name)
    return if (m != null) {
        m.groupValues[5] to "${m.groupValues[1]}  ${m.groupValues[2]}:${m.groupValues[3]} · ${fmtBytes(f.size)}"
    } else {
        f.name to fmtBytes(f.size)
    }
}

@Composable
fun BurnScreen(engine: HeatEngine, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<HeatFile>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<HeatFile>>(emptyList()) }
    var burning by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    var showEgg by remember { mutableStateOf(false) }

    LaunchedEffect(tab, refresh) {
        loaded = false
        selected = emptySet()
        files = withContext(Dispatchers.IO) { engine.burner.list(burned = tab == 1) }
        loaded = true
    }

    LaunchedEffect(burning) {
        if (burning) {
            val t0 = SystemClock.elapsedRealtime()
            val moved = withContext(Dispatchers.IO) { engine.burner.move(pending, toBurned = true) }
            val left = 1800L - (SystemClock.elapsedRealtime() - t0)
            if (left > 0) delay(left)
            burning = false
            outcome = moved
            refresh++
        }
    }

    Column(Modifier.fillMaxSize()) {
        HeatTopBar("Burn", onBack, "Files you received through Heat") {
            if (files.isNotEmpty()) {
                val all = selected.size == files.size
                TextButton(onClick = { selected = if (all) emptySet() else files.map { it.id }.toSet() }) {
                    Text(if (all) "None" else "All")
                }
            }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0; note = null }, text = { Text("Heat files") })
            Tab(selected = tab == 1, onClick = { tab = 1; note = null }, text = { Text("Recently burned") })
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                // Secret: light the fuse by tapping the flame 7 times quickly.
                Box(
                    Modifier.fillMaxWidth().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        val now = SystemClock.elapsedRealtime()
                        taps = if (now - lastTap < 1200L) taps + 1 else 1
                        lastTap = now
                        if (taps >= 7) {
                            taps = 0
                            showEgg = true
                        }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    FlameFlicker(Modifier.size(120.dp))
                }
            }
            if (loaded && files.isEmpty()) {
                item {
                    Text(
                        if (tab == 0) "Nothing here yet. Files you receive over Heat show up here."
                        else "Nothing burned yet.",
                        Modifier.fillMaxWidth().padding(24.dp),
                        color = cs.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            items(files, key = { it.id }) { f ->
                FileRow(f, checked = f.id in selected) {
                    selected = if (f.id in selected) selected - f.id else selected + f.id
                }
            }
        }
        note?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (tab == 0) {
            Button(
                onClick = {
                    pending = files.filter { it.id in selected }
                    confirm = true
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BurnOrange, contentColor = Color.White)
            ) { Text("Burn ${selected.size} selected") }
        } else {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val chosen = files.filter { it.id in selected }
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { engine.burner.move(chosen, toBurned = false) }
                            note = if (n == chosen.size) "Restored $n to Downloads/Heat" else "Restored $n of ${chosen.size}"
                            refresh++
                        }
                    },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Restore ${selected.size}") }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error)
                ) { Text("Delete forever") }
            }
        }
    }

    if (confirmDelete) {
        val chosen = files.filter { it.id in selected }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete forever?") },
            text = {
                Text("${chosen.size} file(s) will be permanently deleted. This time it's for real: there is no recovery.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { engine.burner.delete(chosen) }
                            note = "Deleted $n file(s) forever"
                            refresh++
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    if (showEgg) EggDialog(engine) { showEgg = false }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Would you like to burn these?") },
            text = {
                Column {
                    pending.take(3).forEach {
                        Text(prettyName(it).first, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (pending.size > 3) Text("+${pending.size - 3} more", color = cs.onSurfaceVariant)
                    Text(
                        "Once burned, it cannot be recovered.",
                        Modifier.padding(top = 12.dp),
                        color = cs.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { confirm = false; burning = true },
                    colors = ButtonDefaults.buttonColors(containerColor = BurnOrange, contentColor = Color.White)
                ) { Text("Yes, burn") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("No") } }
        )
    }

    if (burning) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xF00A0F1E)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FlameFlicker(Modifier.size(260.dp))
                    Text("Burning…", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }

    outcome?.let { n ->
        AlertDialog(
            onDismissRequest = { outcome = null },
            title = { Text(if (n > 0) "Sike! 😈" else "Couldn't burn") },
            text = {
                Text(
                    if (n > 0) "It can be recovered. Open the “Recently burned” tab here, or find it in Downloads/Heat/Recently Burned."
                    else "Those files may have been moved or deleted outside Heat."
                )
            },
            confirmButton = { TextButton(onClick = { outcome = null }) { Text("Got it") } }
        )
    }
}

@Composable
private fun FileRow(f: HeatFile, checked: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (title, sub) = remember(f.id) { prettyName(f) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceVariant)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}
