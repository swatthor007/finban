package com.yourteam.guard.harness.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourteam.guard.harness.a11y.ForegroundTracker
import com.yourteam.guard.harness.rules.Prefs

/** Debug UI: edit the watchlist and see every package actually observed on this device. */
@Composable
fun WatchlistSection() {
    val watch by Prefs.watchlist.collectAsState()
    val seen by Prefs.seen.collectAsState()
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Watchlist (${watch.size})", style = MaterialTheme.typography.titleMedium)
        watch.sorted().forEach { p ->
            val wasSeen = p in seen
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (wasSeen) "👁 " else "·  ") + p, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { Prefs.removeWatch(p) }) { Text("remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, label = { Text("package id") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (input.isNotBlank()) { Prefs.addWatch(input); input = "" } }) { Text("Add") }
        }
        TextButton(onClick = { Prefs.resetWatchlist() }) { Text("Reset to defaults") }
        Text("👁 = seen on this device", fontSize = 11.sp)

        Text("Packages seen (${seen.size}) – tap to add", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text("Foreground now: ${ForegroundTracker.pkg ?: "-"}", fontSize = 12.sp)
        seen.sorted().forEach { p ->
            TextButton(onClick = { Prefs.addWatch(p) }, enabled = p !in watch, contentPadding = PaddingValues(0.dp)) {
                Text(p + if (p in watch) "  ✓" else "", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        TextButton(onClick = { Prefs.clearSeen() }) { Text("Clear seen list") }
    }
}
