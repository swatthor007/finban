package devr.shellshock.finban.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Stroke icons copied from the symbols in the design artifact (24-unit viewBox). Tinted by the caller. */
object AppIcons {
    private fun icon(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    val Mark = icon("mark", "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0", "M9.4 12a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0 -5.2 0")
    val Back = icon("back", "M15 5.5L8.5 12l6.5 6.5")
    val Chevron = icon("chev", "M9 5.5l6.5 6.5L9 18.5")
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7.5")
    val Close = icon("close", "M6 6l12 12M18 6L6 18")
    val Eye = icon("eye", "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")
    val Bell = icon("bell", "M6.5 16.5v-5a5.5 5.5 0 0 1 11 0v5l1.5 1.5H5l1.5-1.5zM10 20.5h4")
    val User = icon("user", "M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M4.5 20a7.5 7.5 0 0 1 15 0")
    val Info = SeverityIcons.None
    val Search = icon("search", "M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0", "M15.5 15.5L21 21")
    val Image = icon("image", "M5.5 5h13a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M7.4 10a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0", "M4 17l5-4.5 3.5 3 3-2.5L20 17")
    val Rupee = icon("rupee", "M7 5.5h10M7 9.5h10M7 5.5c5 0 6.5 1.8 6.5 4s-1.5 4-6.5 4l6.5 5.5")
    val Phone = icon("phone", "M5.5 4h3.2l1.6 4-2 1.3a10 10 0 0 0 5.4 5.4l1.3-2 4 1.6v3.2a2 2 0 0 1-2 2A15 15 0 0 1 3.5 6a2 2 0 0 1 2-2z")
    val Sliders = icon("sliders", "M4 7h9M17 7h3M4 17h3M11 17h9", "M13 7a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M7 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0")
    val Sms = icon("sms", "M4 5.5h16v11H10l-4 3.5v-3.5H4z")
    val Link = icon("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val Listen = icon("listen", "M4 9.5v5h3.5l5 4v-13l-5 4H4z", "M16 9a4.5 4.5 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11")
    val Copy = icon("copy", "M11 9h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M5.5 15V6.5a2 2 0 0 1 2-2H15")
    val Globe = icon("globe", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M3 12h18M12 3c3.2 3.2 3.2 14.8 0 18M12 3c-3.2 3.2-3.2 14.8 0 18")
    val Share = icon("share", "M12 15.5V4M7.5 8.5L12 4l4.5 4.5M5 13v6.5h14V13")
}
