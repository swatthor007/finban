package com.yourteam.guard.harness.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourteam.guard.harness.a11y.GuardAccessibilityService
import com.yourteam.guard.harness.calls.CallStateTracker
import com.yourteam.guard.harness.rules.Prefs
import com.yourteam.guard.harness.rules.TriggerRule

@Composable
fun RuleSection() {
    var n by remember { mutableStateOf(Prefs.windowMin.toString()) }
    var cd by remember { mutableStateOf(Prefs.cooldownMin.toString()) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Trigger rule", style = MaterialTheme.typography.titleMedium)
        Text("(offhook | IN_COMMUNICATION | ended ≤ N min) & caller unknown & watchlisted app to foreground", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(n, { n = it; it.toIntOrNull()?.let { v -> Prefs.windowMin = v } }, label = { Text("N (min)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(cd, { cd = it; it.toIntOrNull()?.let { v -> Prefs.cooldownMin = v } }, label = { Text("Cooldown (min)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
        Button(onClick = { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            GuardAccessibilityService.instance?.let { TriggerRule.show(it) } }, 3000) }) { Text("Show question sheet in 3s (ignores rule)") }
        Text("Live: ${CallStateTracker.describe()}", fontSize = 11.sp)
    }
}
