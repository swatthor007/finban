package devr.shellshock.finban.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/**
 * Tiny back-stack navigation. Navigation Compose is not used on purpose: no new dependency for a prototype with a
 * dozen screens. Screens are plain objects; the host renders the top one.
 */
sealed interface Screen {
    // Onboarding (design board 1)
    data object Welcome : Screen
    data object Language : Screen
    data object Role : Screen
    data object TrustedSetup : Screen
    data object Permissions : Screen
    data object Protected : Screen

    // Elder (boards 2, 6, 11)
    data object Home : Screen
    data object Settings : Screen
    data object Privacy : Screen
    data object Trusted : Screen
    data object Paid : Screen
    data object Debug : Screen
    data class CheckDetail(val id: Long) : Screen

    // Caretaker (board 5 steps 7-8; not in the artifact otherwise)
    data object CaretakerHome : Screen
    data class AlertDetail(val alertId: String) : Screen
}

class Navigator(start: Screen) {
    val stack = mutableStateListOf(start)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    /** Replaces the whole stack (e.g. onboarding finished -> Home, so Back never returns to onboarding). */
    fun reset(s: Screen) { stack.clear(); stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}

@Composable
fun rememberNavigator(start: Screen): Navigator = remember { Navigator(start) }

/** Renders the top screen and wires the system Back button to the stack. */
@Composable
fun NavHost(nav: Navigator, content: @Composable (Screen) -> Unit) {
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    content(nav.current)
}
