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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.Font
import com.trevisolli.agendawidget.R

/** Marca Consigliere: creme, vinho e dourado; fofo mas elegante. */
object Brand {
    val Wine = Color(0xFF8A2B3D)
    val WineDeep = Color(0xFF3E0F1B)
    val Gold = Color(0xFFC9A24A)
    val GoldSoft = Color(0xFFE8C877)
    val Cream = Color(0xFFFBF5EC)
    val Ink = Color(0xFF2A2124)
    val Blush = Color(0xFFF6D9D4)
    val Mint = Color(0xFFD9EEDF)
    val Peach = Color(0xFFFCE3CF)
    val Lilac = Color(0xFFE6E0F5)
}

val APP_COLORS = lightColorScheme(
    primary = Brand.Wine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF7E3E1),
    onPrimaryContainer = Color(0xFF4A1220),
    secondary = Color(0xFFA9822F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8EBCB),
    onSecondaryContainer = Color(0xFF4A3608),
    tertiary = Color(0xFF3F7A5C),
    tertiaryContainer = Brand.Mint,
    onTertiaryContainer = Color(0xFF173A28),
    background = Brand.Cream,
    surface = Brand.Cream,
    onSurface = Brand.Ink,
    onBackground = Brand.Ink,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF7A6B6E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    outline = Color(0xFFE2D4C3),
    outlineVariant = Color(0xFFEDE3D6),
    error = Color(0xFFB3261E),
)

private val Fraunces = FontFamily(
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
    Font(R.font.fraunces_bold, FontWeight.Bold),
)
private val Nunito = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_semibold, FontWeight.Medium),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
)

/** Títulos em serifa suave (Fraunces), texto arredondado (Nunito). */
val APP_TYPE = Typography().let { t ->
    fun body(s: TextStyle, w: FontWeight = FontWeight.Normal) = s.copy(fontFamily = Nunito, fontWeight = w)
    fun serif(s: TextStyle, size: Int, w: FontWeight = FontWeight.SemiBold) = s.copy(fontFamily = Fraunces, fontWeight = w, fontSize = size.sp)
    t.copy(
        displaySmall = serif(t.displaySmall, 34, FontWeight.Bold),
        headlineLarge = serif(t.headlineLarge, 30, FontWeight.Bold),
        headlineMedium = serif(t.headlineMedium, 27, FontWeight.Bold),
        headlineSmall = serif(t.headlineSmall, 23),
        titleLarge = serif(t.titleLarge, 20),
        titleMedium = body(t.titleMedium, FontWeight.ExtraBold),
        titleSmall = body(t.titleSmall, FontWeight.Bold),
        bodyLarge = body(t.bodyLarge),
        bodyMedium = body(t.bodyMedium),
        bodySmall = body(t.bodySmall),
        labelLarge = body(t.labelLarge, FontWeight.Bold),
        labelMedium = body(t.labelMedium, FontWeight.Bold),
        labelSmall = body(t.labelSmall, FontWeight.SemiBold),
    )
}

/** Noir: grafite com dourado e vinho. */
val APP_DARK_COLORS = darkColorScheme(
    primary = Brand.GoldSoft,
    onPrimary = Color(0xFF2B1E00),
    primaryContainer = Color(0xFF3A2A12),
    onPrimaryContainer = Color(0xFFF6E2B0),
    secondary = Color(0xFFE59AA8),
    onSecondary = Color(0xFF3E0F1B),
    secondaryContainer = Color(0xFF4A1A26),
    onSecondaryContainer = Color(0xFFF8D9DF),
    tertiary = Color(0xFF9BD3B3),
    tertiaryContainer = Color(0xFF1F3A2C),
    onTertiaryContainer = Color(0xFFD3F0DE),
    background = Color(0xFF141114),
    surface = Color(0xFF141114),
    onSurface = Color(0xFFF3E9DA),
    onBackground = Color(0xFFF3E9DA),
    surfaceVariant = Color(0xFF211C20),
    onSurfaceVariant = Color(0xFFBFB2A8),
    surfaceContainerLowest = Color(0xFF1B171A),
    surfaceContainerLow = Color(0xFF1E1A1D),
    surfaceContainer = Color(0xFF211C20),
    surfaceContainerHigh = Color(0xFF262025),
    surfaceContainerHighest = Color(0xFF2C252A),
    outline = Color(0xFF4A3F45),
    outlineVariant = Color(0xFF332B30),
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
    MaterialTheme(
        colorScheme = if (dark) APP_DARK_COLORS else APP_COLORS,
        typography = APP_TYPE,
        shapes = androidx.compose.material3.Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
