package com.yourteam.guard.harness.ui

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourteam.guard.harness.tts.TtsManager

@Composable
fun TtsSection() {
    val ctx = LocalContext.current
    val status by TtsManager.status.collectAsState()
    LaunchedEffect(Unit) { TtsManager.check(ctx) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Text-to-speech (Tamil)", style = MaterialTheme.typography.titleMedium)
        Text(status, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { TtsManager.speakSample(ctx) }) { Text("Speak Tamil") }
            Button(onClick = { TtsManager.playAsset(ctx) }) { Text("Play sample_ta.mp3") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Install voice data") }
            TextButton(onClick = { runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) } }) { Text("TTS settings") }
        }
    }
}
