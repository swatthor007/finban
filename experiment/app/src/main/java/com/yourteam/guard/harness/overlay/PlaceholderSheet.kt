package com.yourteam.guard.harness.overlay

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yourteam.guard.harness.log.EventLog
import com.yourteam.guard.harness.log.EventType

val placeholderSheet: SheetContent = { dismiss ->
    Text("Possible scam warning (test)", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    Text("• Reason one: you are on a call with an unknown number", style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(4.dp))
    Text("• Reason two: you just opened a payment app", style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(16.dp))
    Button(onClick = { EventLog.log(EventType.OVERLAY, "sheet-touch", "button A"); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Stop – this looks wrong") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { EventLog.log(EventType.OVERLAY, "sheet-touch", "button B"); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("I know this person") }
    Spacer(Modifier.height(8.dp))
    val ctx = androidx.compose.ui.platform.LocalContext.current
    TextButton(onClick = { EventLog.log(EventType.OVERLAY, "sheet-touch", "Listen"); com.yourteam.guard.harness.tts.TtsManager.speakSample(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("🔊 Listen") }
}
