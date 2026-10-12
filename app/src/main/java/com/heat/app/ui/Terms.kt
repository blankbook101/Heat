package com.heat.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

const val TERMS_CONTACT = "lemonad143@gmail.com"

private val TERMS = listOf(
    "Acceptance" to "By tapping \"I agree\" and using Heat, you accept these terms. If you don't agree, don't use the app.",
    "Age" to "You must be 18 or older, or have a parent or guardian's permission.",
    "As is" to "Heat is an experimental app provided \"as is\" and \"as available\". It was built quickly with AI assistance and may contain bugs, fail mid-transfer, lose or corrupt files, or stop working on your device. We make no promise that it is secure, reliable or fit for any purpose.",
    "Your responsibility" to "You alone are responsible for how you use Heat, what you send, what you accept, and what happens on your device. Only accept files from people you trust, because files from strangers can be harmful.",
    "Acceptable use" to "Don't use Heat for anything illegal, to harass anyone, to spread malware, or to share content you have no right to share. Anyone who misuses Heat or its code does so entirely at their own risk and liability.",
    "No liability" to "To the fullest extent the law allows, the developer is not liable for any loss, damage, claim or cost arising from your use or misuse of Heat, including lost data, device damage, third-party actions, or content sent or received. This covers direct, indirect and consequential losses.",
    "Indemnity" to "If your use of Heat leads to a claim against the developer, you agree to cover the resulting costs.",
    "Your name and photo are visible" to "Your username is broadcast over Bluetooth to anyone nearby who has Heat open, and your profile picture (if you set one) can be requested by them. Don't use a name or picture you wouldn't want strangers to see.",
    "Groups" to "Anyone you let into a group can send you files, and files you share in a group reach every member. Only join groups of people you trust. Group leaders are responsible for who they allow in.",
    "Privacy" to "Heat has no accounts and no servers, and the developer collects no data. Messages stay in memory and are not stored. Received files are saved on your device in Downloads/Heat.",
    "Changes" to "These terms may change. Continued use means you accept the updated version.",
    "Law" to "These terms are governed by the laws of India.",
    "Contact" to TERMS_CONTACT
)

@Composable
fun TermsText(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TERMS.forEachIndexed { i, (title, body) ->
            Column {
                Text("${i + 1}. $title", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** First-launch gate: the app stays blocked until the user ticks the box and continues. */
@Composable
fun TermsScreen(onAccept: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text("Terms of Use", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Please read before using Heat.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        TermsText(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("I am 18 or older and I agree to these terms", style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onAccept, enabled = agreed, modifier = Modifier.fillMaxWidth()) { Text("I agree") }
    }
}
