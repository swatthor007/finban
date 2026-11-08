package devr.shellshock.finban.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Severity icons from the design (24-unit stroke icons): triangle = high, diamond = careful, circle = none. Tinted by the caller. */
object SeverityIcons {
    private fun icon(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    val High = icon("high", "M12 3.5 2.8 19.5h18.4L12 3.5z", "M12 10v4.5M12 17.2v.3")
    val Careful = icon("careful", "M12 2.8 21.2 12 12 21.2 2.8 12 12 2.8z", "M12 8v4.5M12 15.7v.3")
    val None = icon("none", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M12 11v5M12 7.8v.3")

    fun forLevel(level: String) = when (level) { "high" -> High; "careful" -> Careful; else -> None }
}
