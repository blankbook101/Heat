package com.heat.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Tiny stroke icons so we don't need the heavy material-icons-extended library. */
object HIcons {
    val Upload: ImageVector by lazy { stroke("M12,16 L12,4 M7,9 L12,4 L17,9 M5,20 L19,20") }
    val Download: ImageVector by lazy { stroke("M12,4 L12,16 M7,11 L12,16 L17,11 M5,20 L19,20") }
    val Attach: ImageVector by lazy {
        stroke("M20,11.5 L12,19.5 A4.5,4.5 0 0 1 5.6,13.1 L13.6,5.1 A3,3 0 0 1 17.9,9.4 L9.9,17.4 A1.5,1.5 0 0 1 7.8,15.3 L15,8.1")
    }

    private fun stroke(path: String): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f
        ).addPath(
            pathData = addPathNodes(path),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ).build()
}
