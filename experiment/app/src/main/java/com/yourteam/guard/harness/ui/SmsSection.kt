package com.yourteam.guard.harness.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourteam.guard.harness.rules.Prefs

/** Editable list of SMS-app package ids the notification listener should read (OEM apps differ). */
@Composable
fun SmsSection() {
    val pk by Prefs.smsPackages.collectAsState()
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("SMS apps read by notification listener", style = MaterialTheme.typography.titleMedium)
        pk.sorted().forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { Prefs.setSmsPackages(pk - p) }) { Text("remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, label = { Text("SMS app package") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (input.isNotBlank()) { Prefs.setSmsPackages(pk + input.trim()); input = "" } }) { Text("Add") }
        }
    }
}
