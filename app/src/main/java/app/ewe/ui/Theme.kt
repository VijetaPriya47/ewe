package app.ewe.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.ewe.R

object C {
    val Ink = Color(0xFF111827)
    val G700 = Color(0xFF374151)
    val G600 = Color(0xFF4B5563)
    val G500 = Color(0xFF6B7280)
    val G400 = Color(0xFF9CA3AF)
    val G300 = Color(0xFFD1D5DB)
    val G200 = Color(0xFFE5E7EB)
    val G100 = Color(0xFFF3F4F6)
    val G50 = Color(0xFFF9FAFB)
    val White = Color(0xFFFFFFFF)
    val Blue = Color(0xFF1D4ED8)
    val BlueBg = Color(0xFFEFF6FF)
    val BlueDot = Color(0xFF2563EB)
    val Purple = Color(0xFF6D28D9)
    val PurpleBg = Color(0xFFF5F3FF)
    val Green = Color(0xFF166534)
    val GreenBg = Color(0xFFDCFCE7)
    val Red = Color(0xFF991B1B)
    val RedBg = Color(0xFFFEE2E2)
    val RedLine = Color(0xFFFCA5A5)
    val Amber = Color(0xFF92400E)
    val AmberBg = Color(0xFFFFFBEB)
    val Mark = Color(0xFFFEF3C7)
}

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter, weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

fun ts(size: Int, weight: Int = 400, color: Color = C.Ink, lineHeight: Float = 0f) = TextStyle(
    fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight(weight), color = color,
    lineHeight = if (lineHeight > 0f) (size * lineHeight).sp else TextStyle.Default.lineHeight,
)

@Composable
fun EweTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val type = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Inter), displayMedium = base.displayMedium.copy(fontFamily = Inter),
        displaySmall = base.displaySmall.copy(fontFamily = Inter), headlineLarge = base.headlineLarge.copy(fontFamily = Inter),
        headlineMedium = base.headlineMedium.copy(fontFamily = Inter), headlineSmall = base.headlineSmall.copy(fontFamily = Inter),
        titleLarge = base.titleLarge.copy(fontFamily = Inter), titleMedium = base.titleMedium.copy(fontFamily = Inter),
        titleSmall = base.titleSmall.copy(fontFamily = Inter), bodyLarge = base.bodyLarge.copy(fontFamily = Inter),
        bodyMedium = base.bodyMedium.copy(fontFamily = Inter), bodySmall = base.bodySmall.copy(fontFamily = Inter),
        labelLarge = base.labelLarge.copy(fontFamily = Inter), labelMedium = base.labelMedium.copy(fontFamily = Inter),
        labelSmall = base.labelSmall.copy(fontFamily = Inter),
    )
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.Ink, onPrimary = C.White, secondary = C.G700, onSecondary = C.White,
            background = C.White, onBackground = C.Ink, surface = C.White, onSurface = C.Ink,
            surfaceVariant = C.G100, onSurfaceVariant = C.G600, outline = C.G300, outlineVariant = C.G200,
            error = C.Red, onError = C.White, surfaceContainer = C.White, surfaceContainerHigh = C.White,
            surfaceContainerHighest = C.G100, surfaceContainerLow = C.White, surfaceContainerLowest = C.White,
            primaryContainer = C.G100, onPrimaryContainer = C.Ink, secondaryContainer = C.G100, onSecondaryContainer = C.Ink,
            surfaceTint = C.White,
        ),
        typography = type, content = content,
    )
}
