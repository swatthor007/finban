package devr.shellshock.finban.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import devr.shellshock.finban.R

/**
 * Hero image (612x612 JPEG on pure black) with the black made invisible.
 * A Screen blend only hides black on dark backgrounds (on white it would erase the whole image), so luminance is
 * turned into alpha instead: black -> transparent, bright glass -> opaque. Works on light and dark canvases.
 */
private val BlackToTransparent = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0.5f, 0.5f, 1.7f, 0f, -34f, // alpha = 0.5 R + 0.5 G + 1.7 B - 34/255: blue-weighted (the glass is blue), offset kills JPEG noise near black
)))

@Composable
fun GlassShield(size: Dp = 200.dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Image(
        painterResource(R.drawable.glass_shield), contentDescription,
        modifier = modifier.size(size), colorFilter = BlackToTransparent,
    )
}
