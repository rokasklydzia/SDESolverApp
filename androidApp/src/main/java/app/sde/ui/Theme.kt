package app.sde.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

@Composable
fun SdeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        androidx.compose.runtime.CompositionLocalProvider(LocalChartColors provides if (dark) DarkChart else LightChart) {
            content()
        }
    }
}
