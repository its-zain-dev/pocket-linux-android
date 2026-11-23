package com.pocketlinux.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object PocketLinuxColors {
    val Obsidian = Color(0xFF090D16)
    val DeepConsole = Color(0xFF050811)
    val Surface = Color(0xFF111827)
    val SurfaceElevated = Color(0xFF1E293B)
    val SurfaceHighlight = Color(0xFF263348)
    val Border = Color(0xFF273549)
    val BorderSubtle = Color(0xFF1E293B)

    val Cyan = Color(0xFF38BDF8)
    val CyanDeep = Color(0xFF0EA5E9)
    val CyanContainer = Color(0xFF0C2D48)
    val Emerald = Color(0xFF10B981)
    val EmeraldContainer = Color(0xFF064E3B)
    val Amber = Color(0xFFF59E0B)
    val AmberContainer = Color(0xFF451A03)
    val Rose = Color(0xFFF87171)
    val RoseContainer = Color(0xFF450A0A)
    val Indigo = Color(0xFF818CF8)
    val Teal = Color(0xFF2DD4BF)

    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

private val PocketLinuxColorScheme = darkColorScheme(
    primary = PocketLinuxColors.Cyan,
    onPrimary = Color(0xFF062033),
    primaryContainer = PocketLinuxColors.CyanContainer,
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = PocketLinuxColors.Indigo,
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = PocketLinuxColors.Emerald,
    onTertiary = Color(0xFF022C22),
    tertiaryContainer = PocketLinuxColors.EmeraldContainer,
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = PocketLinuxColors.Obsidian,
    onBackground = PocketLinuxColors.TextPrimary,
    surface = PocketLinuxColors.Surface,
    onSurface = PocketLinuxColors.TextPrimary,
    surfaceVariant = PocketLinuxColors.SurfaceElevated,
    onSurfaceVariant = PocketLinuxColors.TextSecondary,
    outline = PocketLinuxColors.Border,
    outlineVariant = PocketLinuxColors.BorderSubtle,
    error = PocketLinuxColors.Rose,
    onError = Color(0xFF450A0A),
    errorContainer = PocketLinuxColors.RoseContainer,
    onErrorContainer = Color(0xFFFECACA)
)

private val PocketLinuxTypography = Typography(
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp
    )
)

private val PocketLinuxShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp)
)

@Composable
fun PocketLinuxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PocketLinuxColorScheme,
        typography = PocketLinuxTypography,
        shapes = PocketLinuxShapes,
        content = content
    )
}
