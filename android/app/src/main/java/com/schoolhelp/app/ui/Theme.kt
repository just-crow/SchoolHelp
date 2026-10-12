package com.schoolhelp.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.sp
import com.schoolhelp.app.R

val Paper = Color(0xFFF5F3EC)
val Ink = Color(0xFF212D27)
val Forest = Color(0xFF365C49)
val Muted = Color(0xFF72786D)
val Line = Color(0xFFDFE2D6)
val Sage = Color(0xFFE6EBDD)
val Night = Color(0xFF111C17)
val Cream = Color(0xFFECEFE2)
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val Body = FontFamily(
    Font(R.font.manrope, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)
val Editorial = FontFamily(Font(R.font.lora))

@Composable
fun SchoolHelpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Forest, onPrimary = Color.White,
            background = Paper, onBackground = Ink,
            surface = Paper, onSurface = Ink,
            surfaceVariant = Sage, onSurfaceVariant = Muted,
            outline = Line, secondary = Forest,
            primaryContainer = Sage, onPrimaryContainer = Forest,
            secondaryContainer = Sage, onSecondaryContainer = Forest,
            tertiary = Forest, tertiaryContainer = Sage, onTertiaryContainer = Forest,
        ),
        typography = Typography(
            displayMedium = TextStyle(fontFamily = Editorial, fontSize = 44.sp, lineHeight = 50.sp),
            headlineLarge = TextStyle(fontFamily = Editorial, fontSize = 34.sp, lineHeight = 41.sp),
            headlineMedium = TextStyle(fontFamily = Editorial, fontSize = 28.sp, lineHeight = 35.sp),
            titleLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
            titleMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            bodyLarge = TextStyle(fontFamily = Body, fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 22.sp),
            labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 14.sp),
            labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = .6.sp),
            bodySmall = TextStyle(fontFamily = Body, fontSize = 12.sp, lineHeight = 18.sp),
        ),
        content = content,
    )
}
