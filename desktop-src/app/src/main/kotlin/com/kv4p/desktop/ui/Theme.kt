package com.kv4p.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/** Colors from the Android app's res/values/colors.xml. */
object Kv4pColors {
    val Black = Color(0xFF000000)
    val MediumGray = Color(0xFF353533)
    val Primary = Color(0xFFFFFCEA)
    val PrimaryDeselected = Color(0xCEFFFCEA)
    val PrimaryVeryFaint = Color(0x0DFFFCEA)
    val Accent = Color(0xFFAD5642)
    val AccentVeryFaint = Color(0x55AD5642)
    val OverS9 = Color(0xFFCCBD4C)
    val Overload = Color(0xFFAD5642)
}

private fun loadJost(): FontFamily {
    val bytes = Thread.currentThread().contextClassLoader
        ?.getResourceAsStream("font/jost_variable.ttf")
        ?.use { it.readBytes() }
        ?: return FontFamily.Default
    return FontFamily(
        Font(identity = "jost-regular", data = bytes, weight = FontWeight.Normal),
        Font(identity = "jost-medium", data = bytes, weight = FontWeight.Medium),
        Font(identity = "jost-light", data = bytes, weight = FontWeight.Light),
    )
}

val Jost: FontFamily by lazy { loadJost() }

@Composable
fun Kv4pTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Jost),
        displayMedium = base.displayMedium.copy(fontFamily = Jost),
        headlineMedium = base.headlineMedium.copy(fontFamily = Jost),
        titleMedium = base.titleMedium.copy(fontFamily = Jost),
        titleSmall = base.titleSmall.copy(fontFamily = Jost),
        bodyLarge = base.bodyLarge.copy(fontFamily = Jost),
        bodyMedium = base.bodyMedium.copy(fontFamily = Jost),
        bodySmall = base.bodySmall.copy(fontFamily = Jost),
        labelLarge = base.labelLarge.copy(fontFamily = Jost),
        labelMedium = base.labelMedium.copy(fontFamily = Jost),
    )
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Kv4pColors.Primary,
            onPrimary = Kv4pColors.Black,
            secondary = Kv4pColors.Accent,
            onSecondary = Kv4pColors.Primary,
            background = Kv4pColors.Black,
            onBackground = Kv4pColors.Primary,
            surface = Kv4pColors.Black,
            onSurface = Kv4pColors.Primary,
            surfaceVariant = Kv4pColors.MediumGray,
            onSurfaceVariant = Kv4pColors.PrimaryDeselected,
            outline = Kv4pColors.PrimaryDeselected,
            error = Kv4pColors.Accent,
        ),
        typography = typography,
        content = content,
    )
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace)
