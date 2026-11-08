package com.yourteam.guard.harness.rules

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

import com.yourteam.guard.harness.a11y.GuardAccessibilityService
import com.yourteam.guard.harness.calls.CallStateTracker
import com.yourteam.guard.harness.log.EventLog
import com.yourteam.guard.harness.log.EventType
import org.json.JSONObject

/**
 * Fire when (cellular off-hook OR audio MODE_IN_COMMUNICATION OR either ended within N min)
 *   AND the caller is unknown AND a watchlisted app has just come to the foreground,
 * subject to a cooldown. The only action is showing our own bottom sheet.
 *
 * "Unknown" rules: cellular call -> unknown unless the screening service saw the number AND it is in contacts
 * (no screening info counts as unknown, flagged in the log). VoIP-only -> no number is available, so treated as unknown.
 */
object TriggerRule {
    @Volatile private var lastFiredMs = 0L

    fun onForeground(svc: GuardAccessibilityService, pkg: String, watched: Boolean) {
        if (!watched) return
        val now = System.currentTimeMillis()
        val t = CallStateTracker
        val windowMs = Prefs.windowMin * 60_000L
        val cooldownMs = Prefs.cooldownMin * 60_000L

        val cellularInvolved = t.offHook || (t.lastCellularEndMs > 0 && now - t.lastCellularEndMs <= windowMs)
        val voipInvolved = t.voipActive || (t.lastVoipEndMs > 0 && now - t.lastVoipEndMs <= windowMs)
        val callCond = cellularInvolved || voipInvolved
        val unknownNote: String
        val callerUnknown = when {
            cellularInvolved && t.lastNumberKnown == true -> { unknownNote = "cellular caller IS in contacts"; voipInvolved }
            cellularInvolved -> { unknownNote = if (t.lastNumberKnown == null) "cellular, no screening info -> assumed unknown" else "cellular caller not in contacts"; true }
            voipInvolved -> { unknownNote = "VoIP only, no number available -> assumed unknown"; true }
            else -> { unknownNote = "n/a"; false }
        }
        val cooling = lastFiredMs > 0 && now - lastFiredMs < cooldownMs
        val fire = callCond && callerUnknown && !cooling
        val reason = when { !callCond -> "no active/recent call"; !callerUnknown -> "caller known"; cooling -> "cooldown (${(cooldownMs - (now - lastFiredMs)) / 1000}s left)"; else -> "FIRE" }
        EventLog.log(EventType.RULE, if (fire) "fire" else "skip", "watched app=$pkg callCond=$callCond (${t.describe()}) unknown=$callerUnknown [$unknownNote] -> $reason",
            JSONObject().put("pkg", pkg).put("cellularInvolved", cellularInvolved).put("voipInvolved", voipInvolved)
                .put("callerUnknown", callerUnknown).put("cooling", cooling).put("N_min", Prefs.windowMin).put("cooldown_min", Prefs.cooldownMin).put("fired", fire))
        if (fire) { lastFiredMs = now; show(svc) }
    }

    fun show(svc: GuardAccessibilityService) = svc.sheet.show(questionSheet)

    private val questions = listOf(
        "Did the caller ask you to install an app or share your screen?",
        "Did they ask for an OTP, PIN or card details?",
        "Did they say it is urgent or threaten you?",
    )

    private val questionSheet: com.yourteam.guard.harness.overlay.SheetContent = { dismiss ->
        var idx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
        androidx.compose.material3.Text("Quick check (${minOf(idx + 1, 3)} of 3)", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
        if (idx < questions.size) {
            androidx.compose.material3.Text(questions[idx], style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
            for (ans in listOf("Yes", "No")) {
                androidx.compose.material3.Button(onClick = {
                    EventLog.log(EventType.RULE, "answer", "Q${idx + 1} \"${questions[idx]}\" = $ans"); idx++
                }, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) { androidx.compose.material3.Text(ans) }
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
            }
        } else {
            androidx.compose.material3.Text("Thanks. (Test build: no further action is taken.)", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = dismiss, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) { androidx.compose.material3.Text("Close") }
        }
    }
}
