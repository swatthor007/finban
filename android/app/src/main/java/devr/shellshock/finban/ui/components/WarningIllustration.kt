package devr.shellshock.finban.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import devr.shellshock.finban.R

/** 3D red warning triangle (360x360 PNG, already transparent, so no blending needed). Decorative: severity is always also shown as icon + words. */
@Composable
fun WarningIllustration(size: Dp = 72.dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.warning_icon), contentDescription = null, modifier = modifier.size(size))
}
