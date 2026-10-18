package com.trevisolli.agendawidget.ui

import androidx.compose.material3.Typography
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.trevisolli.agendawidget.data.AppTheme
import com.trevisolli.agendawidget.data.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Tons de aquarela: papel creme, verde de campo, azul de céu. */
val APP_COLORS = lightColorScheme(
    primary = Color(0xFF3E7C59),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E8D3),
    onPrimaryContainer = Color(0xFF1C3A28),
    secondary = Color(0xFF5E93B8),
    secondaryContainer = Color(0xFFD6E8F3),
    onSecondaryContainer = Color(0xFF1E3A4F),
    background = Color(0xFFF4EFE2),
    surface = Color(0xFFF4EFE2),
    onSurface = Color(0xFF2D3A31),
    onBackground = Color(0xFF2D3A31),
    surfaceVariant = Color(0xFFFFFBF2),
    onSurfaceVariant = Color(0xFF55615A),
    surfaceContainerHighest = Color(0xFFFFFBF2),
    surfaceContainerHigh = Color(0xFFFFFBF2),
    outline = Color(0xFFB9B29E),
    error = Color(0xFFB3261E),
)

val APP_TYPE = Typography().let { t ->
    t.copy(
        headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 30.sp),
        headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp),
        titleMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    )
}

/** Versão noturna: verde-musgo sobre quase preto, sem perder o tom de aquarela. */
val APP_DARK_COLORS = darkColorScheme(
    primary = Color(0xFF8CC9A4),
    onPrimary = Color(0xFF0B2416),
    primaryContainer = Color(0xFF22402F),
    onPrimaryContainer = Color(0xFFCDEBD7),
    secondary = Color(0xFF9CC4E0),
    secondaryContainer = Color(0xFF24394A),
    onSecondaryContainer = Color(0xFFD6E8F3),
    background = Color(0xFF121614),
    surface = Color(0xFF121614),
    onSurface = Color(0xFFE3E8E4),
    onBackground = Color(0xFFE3E8E4),
    surfaceVariant = Color(0xFF1E2420),
    onSurfaceVariant = Color(0xFFB4BDB6),
    surfaceContainerHighest = Color(0xFF1E2420),
    surfaceContainerHigh = Color(0xFF1E2420),
    outline = Color(0xFF4A554D),
    error = Color(0xFFFFB4AB),
)

fun isDarkTheme(mode: AppTheme, systemDark: Boolean) = when (mode) {
    AppTheme.LIGHT -> false
    AppTheme.DARK -> true
    AppTheme.SYSTEM -> systemDark
}

/** Tema do app conforme a escolha nas Configurações (claro, escuro ou do celular). */
@Composable
fun ConsigliereTheme(content: @Composable () -> Unit) {
    val dark = isDarkTheme(Settings(LocalContext.current).appTheme, isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) APP_DARK_COLORS else APP_COLORS, typography = APP_TYPE, content = content)
}
