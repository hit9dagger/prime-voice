package cz.primevoice.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Lime = Color(0xFFB9F56A)
val Background = Color(0xFF0B1017)
val Panel = Color(0xFF151D27)
val Muted = Color(0xFF9BA8B8)
val Outline = Color(0xFF293341)

@Composable fun PrimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Lime, onPrimary = Color(0xFF17220A), background = Background,
        onBackground = Color(0xFFF2F5F7), surface = Panel, onSurface = Color(0xFFF2F5F7),
        surfaceVariant = Color(0xFF202B38), onSurfaceVariant = Muted, outline = Outline,
        error = Color(0xFFFFB4AB)
    ), typography = Typography(
        headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-1).sp),
        headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.4.sp)
    ), content = content)
}
