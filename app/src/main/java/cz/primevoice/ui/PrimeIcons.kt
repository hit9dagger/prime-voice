package cz.primevoice.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small local vector set; avoids shipping the entire Material Icons extended catalogue. */
object PrimeIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd).build()
    val GraphicEq = icon("Zvuk", "M2,9h3v6H2z M6,5h3v14H6z M10.5,2h3v20h-3z M15,6h3v12h-3z M19,9h3v6h-3z")
    val LiveTv = icon("Televize", "M2,5h20v14H2z M4,7v10h16V7z M8,21v-2h8v2z M7,2l1.5,-1l4,4l-1.5,1z M17,2l-1.5,-1l-4,4l1.5,1z")
    val Info = icon("Informace", "M12,2a10,10 0,1 0,0,20a10,10 0,0 0,0,-20z M12,4a8,8 0,1 1,0,16a8,8 0,0 1,0,-16z M11,10h2v7h-2z M11,6h2v2h-2z")
    val Stop = icon("Zastavit", "M6,6h12v12H6z")
    val PlayArrow = icon("Spustit", "M7,4l14,8l-14,8z")
    val Download = icon("Stáhnout", "M11,2h2v10l3,-3l1.5,1.5l-5.5,5.5l-5.5,-5.5L8,9l3,3z M4,17h2v3h12v-3h2v5H4z")
    val CropFree = icon("Oblast", "M3,3h6v2H5v4H3z M15,3h6v6h-2V5h-4z M3,15h2v4h4v2H3z M19,15h2v6h-6v-2h4z")
    val Mic = icon("Mikrofon", "M9,3a3,3 0,0 1,6,0v9a3,3 0,0 1,-6,0z M5,11h2v1a5,5 0,0 0,10,0v-1h2v1a7,7 0,0 1,-6,6.93V22h-2v-3.07A7,7 0,0 1,5,12z")
    val ExpandMore = icon("Rozbalit", "M5,8l7,7l7,-7l1.5,1.5L12,18L3.5,9.5z")
    val VolumeUp = icon("Hlas", "M3,9h4l5,-4v14l-5,-4H3z M15,6a7,7 0,0 1,0,12v-2.5a4.5,4.5 0,0 0,0,-7z M18,2a11,11 0,0 1,0,20v-2.3a9,9 0,0 0,0,-15.4z")
    val Replay = icon("Zopakovat", "M6,5V1L1,6l5,5V7a7,7 0,1 1,-1,10l-1.5,1.4A9,9 0,1 0,6,5z")
    val ClearAll = icon("Vyprázdnit", "M4,6h16v2H4z M6,11h12v2H6z M8,16h8v2H8z")
    val Tune = icon("Nastavit", "M2,6h20v2H2z M2,16h20v2H2z M6,3h3v8H6z M15,13h3v8h-3z")
}
