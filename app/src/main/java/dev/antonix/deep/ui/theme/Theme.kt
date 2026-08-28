package dev.antonix.deep.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Void = Color(0xFF07070B)
val VoidElevated = Color(0xFF12121A)
val Copper = Color(0xFFE8A87C)
val CopperDim = Color(0xFFC4845A)
val Mist = Color(0xFFB7B4C7)
val MistDim = Color(0xFF6E6A80)
val Ember = Color(0xFFFF6B4A)

private val colors = darkColorScheme(
    primary = Copper,
    onPrimary = Void,
    secondary = CopperDim,
    background = Void,
    surface = VoidElevated,
    onBackground = Color(0xFFF4F1EA),
    onSurface = Color(0xFFF4F1EA),
    onSurfaceVariant = Mist,
    outline = Color(0xFF2A2A36),
)

@Composable
fun DeepTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography.copy(
            displayLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Light,
                fontSize = 64.sp,
                letterSpacing = (-1.5).sp,
            ),
            headlineMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                letterSpacing = 0.2.sp,
            ),
            bodyLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
            labelLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                letterSpacing = 1.4.sp,
            ),
        ),
        content = content,
    )
}
