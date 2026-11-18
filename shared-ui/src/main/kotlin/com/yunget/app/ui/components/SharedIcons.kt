package com.yunget.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small vector assets shared by the Android and desktop navigation. */
internal object SharedIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black),
        ).build()

    val link = icon("Link",
        "M7 7h4v2H7a3 3 0 0 0 0 6h4v2H7A5 5 0 0 1 7 7zm6 0h4a5 5 0 0 1 0 10h-4v-2h4a3 3 0 0 0 0 -6h-4zM8 11h8v2H8z")
    val close = icon("Close", "M6 4.6L12 10.6 18 4.6 19.4 6 13.4 12 19.4 18 18 19.4 12 13.4 6 19.4 4.6 18 10.6 12 4.6 6z")
    private const val CLOUD = "M6 19a5 5 0 0 1 -.4 -10A7 7 0 0 1 19 10a4.5 4.5 0 0 1 -.5 9z"
    val cloud = icon("Cloud", CLOUD)
    val cloudOutline = icon("Cloud outline", CLOUD +
        "M6 17h12.5a2.5 2.5 0 0 0 .3 -5l-1.4-.2-.3-1.4a5 5 0 0 0 -9.7 -.4L7 11H6a3 3 0 1 0 0 6z")
    val download = icon("Download", "M9 3h6v7h4l-7 7-7-7h4zM5 19h14v2H5z")
    val downloadOutline = icon("Download outline", "M9 3h6v7h4l-7 7-7-7h4zM11 5v7H9.8l2.2 2.3 2.2-2.3H13V5zM5 19h14v2H5z")
    private const val GEAR =
        "M9 2h6l.6 3 2 .9 2.6-1 3 5.2-2.1 2.1v1.8l2.1 2.1-3 5.2-2.6-1-2 .9-.6 2.8H9l-.6-2.8-2-.9-2.6 1-3-5.2L2.9 14v-1.8L.8 10.1l3-5.2 2.6 1 2-.9z"
    val settings = icon("Settings", GEAR + "M12 8.8a3.2 3.2 0 1 0 0 6.4 3.2 3.2 0 0 0 0 -6.4z")
    val settingsOutline = icon("Settings outline", GEAR +
        "M10.6 4h2.8l.5 2.5 3.6 1.6 2-.8 1.3 2.3-1.7 1.7v3.6l1.7 1.7-1.3 2.3-2-.8-3.6 1.6-.5 2.3h-2.8l-.5-2.3-3.6-1.6-2 .8-1.3-2.3 1.7-1.7v-3.6L3.2 9.6l1.3-2.3 2 .8 3.6-1.6z" +
        "M12 8.6a3.4 3.4 0 1 0 0 6.8 3.4 3.4 0 0 0 0 -6.8zM12 10.6a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0 -2.8z")
}
