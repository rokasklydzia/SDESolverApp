package app.sde.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colours the charts use, separate from Material's so paths read well in both themes. */
@Immutable
data class ChartColors(
    val path: Color,
    val highlight: Color,
    val mean: Color,
    val band: Color,
    val histogram: Color,
    val density: Color,
    val axis: Color,
    val grid: Color,
    val label: Color,
    /** Distinct colours for curves compared side by side, e.g. left, mid and right point sums. */
    val series: List<Color>,
)

private val DarkChart = ChartColors(
    path = Color(0xFF6EA8FE),
    highlight = Color(0xFFFFB454),
    mean = Color(0xFFF2F2F2),
    band = Color(0xFF6EA8FE),
    histogram = Color(0xFF6EA8FE),
    density = Color(0xFFFFB454),
    axis = Color(0xFF8A8F98),
    grid = Color(0x22FFFFFF),
    label = Color(0xFFB5BAC1),
    series = listOf(Color(0xFF6EA8FE), Color(0xFFFFB454), Color(0xFF5FD39B), Color(0xFFF07FAF)),
)

private val LightChart = ChartColors(
    path = Color(0xFF2563EB),
    highlight = Color(0xFFD9480F),
    mean = Color(0xFF111827),
    band = Color(0xFF2563EB),
    histogram = Color(0xFF2563EB),
    density = Color(0xFFD9480F),
    axis = Color(0xFF6B7280),
    grid = Color(0x1A000000),
    label = Color(0xFF4B5563),
    series = listOf(Color(0xFF2563EB), Color(0xFFD9480F), Color(0xFF15803D), Color(0xFFBE185D)),
)

val LocalChartColors = staticCompositionLocalOf { DarkChart }

// The app's own colours, built around the charts' blue paths and amber highlights, in place of
// Material's default purple. The app icon's background is the dark navy.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF002A66),
    primaryContainer = Color(0xFF1E3A6E),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFB9C6DC),
    onSecondary = Color(0xFF233041),
    secondaryContainer = Color(0xFF2A3A55),
    onSecondaryContainer = Color(0xFFD8E2FF),
    tertiary = Color(0xFFFFB454),
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF5C3F00),
    onTertiaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF0F1217),
    onBackground = Color(0xFFE3E5EA),
    surface = Color(0xFF0F1217),
    onSurface = Color(0xFFE3E5EA),
    surfaceVariant = Color(0xFF262B33),
    onSurfaceVariant = Color(0xFFB5BAC1),
    surfaceContainerLowest = Color(0xFF0A0C10),
    surfaceContainerLow = Color(0xFF13161C),
    surfaceContainer = Color(0xFF171B21),
    surfaceContainerHigh = Color(0xFF1D2129),
    surfaceContainerHighest = Color(0xFF262B33),
    outline = Color(0xFF8A8F98),
    outlineVariant = Color(0xFF3A4049),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0A2A6B),
    secondary = Color(0xFF4B5D7A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE3F2),
    onSecondaryContainer = Color(0xFF1A2638),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE2C2),
    onTertiaryContainer = Color(0xFF3D2200),
    background = Color(0xFFFAFBFD),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFAFBFD),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE6E9EF),
    onSurfaceVariant = Color(0xFF4B5563),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F6F9),
    surfaceContainer = Color(0xFFEEF1F5),
    surfaceContainerHigh = Color(0xFFE8EBF0),
    surfaceContainerHighest = Color(0xFFE2E6EC),
    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFFCBD0D8),
)

@Composable
fun SdeTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
        CompositionLocalProvider(LocalChartColors provides if (dark) DarkChart else LightChart) {
            content()
        }
    }
}
