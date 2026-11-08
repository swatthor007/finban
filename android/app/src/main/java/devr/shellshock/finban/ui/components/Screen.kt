package devr.shellshock.finban.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import devr.shellshock.finban.ui.theme.Finban

/** Standard screen: canvas background, safe insets, 16dp side padding, scrolls when long. Bottom buttons go in [bottom]. */
@Composable
fun ScreenScaffold(bottom: @Composable ColumnScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Finban.colors.canvas) {
        Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = bottom)
        }
    }
}
