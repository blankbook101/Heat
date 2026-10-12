package com.heat.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.heat.app.R
import com.heat.app.core.HeatEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Minecraft-style gold (the game's "§6" colour). Only shown once the easter egg is found. */
val GoldColor = Color(0xFFFFAA00)

/** Monocraft (SIL OFL 1.1, see assets/licenses): a free font that looks like Minecraft's. */
val McFont = FontFamily(Font(R.font.monocraft))

/** Plain black/white (theme text colour) unless [gold]; gold names use the blocky font with a drop shadow. */
@Composable
fun NameText(
    text: String,
    gold: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1
) {
    if (gold) {
        Text(
            text,
            modifier,
            style = style.copy(
                fontFamily = McFont,
                color = GoldColor,
                shadow = Shadow(Color(0xFF3F2A00), Offset(3f, 3f), 0f)
            ),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    } else {
        Text(text, modifier, style = style, fontWeight = fontWeight, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** Circle picture: the secret cow photo (when [egg]), a photo, or the first letter on an aurora gradient. */
@Composable
fun Avatar(name: String, egg: Boolean, bmp: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        when {
            egg -> Image(
                painter = painterResource(R.drawable.egg_pfp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            bmp != null -> Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            else -> Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(auroraColors())),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name.take(1).uppercase(),
                    color = cs.background,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.42f).sp
                )
            }
        }
    }
}

// ===================================================================== profile screen

@Composable
private fun AvatarChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .border(2.dp, if (selected) cs.primary else Color.Transparent, CircleShape)
                .padding(3.dp)
                .alpha(if (enabled) 1f else 0.35f)
        ) { content() }
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) cs.primary else cs.onSurfaceVariant)
    }
}

@Composable
fun ProfileScreen(engine: HeatEngine, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val name by engine.name.collectAsState()
    val egg by engine.eggFound.collectAsState()
    val mode by engine.avatarMode.collectAsState()
    val photo by engine.myAvatar.collectAsState()
    var text by remember(name) { mutableStateOf(name) }
    var failed by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { engine.setAvatar(uri) }
                failed = !ok
            }
        }
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HeatTopBar("Profile", onBack)
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(
                name, egg = mode == 2 && egg, bmp = if (mode == 1) photo else null, size = 128.dp,
                modifier = Modifier.clickable(onClick = pick)
            )
            Spacer(Modifier.height(8.dp))
            Text("Tap the picture to choose a photo", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AvatarChoice("Initial", mode == 0, true, { engine.setAvatarMode(0) }) {
                    Avatar(name, false, null, 52.dp)
                }
                AvatarChoice("Photo", mode == 1, photo != null, { engine.setAvatarMode(1) }) {
                    Avatar(name, false, photo, 52.dp)
                }
                if (egg) {
                    AvatarChoice("Highland", mode == 2, true, { engine.setAvatarMode(2) }) {
                        Avatar(name, true, null, 52.dp)
                    }
                }
            }
            Row {
                TextButton(onClick = pick) { Text("Choose photo") }
                if (photo != null) TextButton(onClick = { engine.removeAvatar() }) { Text("Remove photo") }
            }
            if (failed) Text("Couldn't use that picture", color = cs.error, style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 16) text = it },
                singleLine = true,
                label = { Text("Your name") },
                supportingText = { Text("${text.length}/16") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { engine.setName(text) },
                enabled = text.isNotBlank() && text.trim() != name,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save name") }

            Spacer(Modifier.height(20.dp))
            NameText(name, egg, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Your name and photo are visible to anyone nearby who has Heat open.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (egg) {
                Spacer(Modifier.height(10.dp))
                Text("Easter egg found", color = GoldColor, fontFamily = McFont, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ===================================================================== easter egg

@Composable
fun EggDialog(engine: HeatEngine, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        engine.foundEgg()
        shown = true
    }
    val s by animateFloatAsState(if (shown) 1f else 0.4f, spring(dampingRatio = 0.45f, stiffness = 120f), label = "egg")
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF2050A14)), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.egg_pfp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(260.dp)
                        .graphicsLayer {
                            scaleX = s
                            scaleY = s
                            alpha = s.coerceIn(0f, 1f)
                        }
                        .clip(RoundedCornerShape(24.dp))
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "You found the secret!",
                    color = GoldColor,
                    fontFamily = McFont,
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Moo! Your name is now gold in blocky letters, and the Highland cow profile picture is unlocked.",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { engine.setAvatarMode(2); onDismiss() }) { Text("Equip it") }
                TextButton(onClick = onDismiss) { Text("Later", color = Color.White) }
            }
        }
    }
}
