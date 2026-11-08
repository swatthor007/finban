package devr.shellshock.finban.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import devr.shellshock.finban.R

/** The real logo (logos/Finban main.png: shield + rupee mark on brand pink), used as is wherever a small mark is needed. */
@Composable
fun AppLogo(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.finban_logo), contentDescription = null,
        modifier = modifier.size(size).clip(RoundedCornerShape(size / 4)),
    )
}
