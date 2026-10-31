package app.sde.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sde.chart.Ticks
import sde.expr.Expr
import sde.expr.toTex
import java.text.DecimalFormatSymbols

// Small pieces shared by the Simulate and Labs screens.

@Composable
fun FormulaField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = modifier.padding(vertical = 2.dp),
    )
}

@Composable
fun Formula(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/**
 * A titled card for one part of a solver: what it calculates, its inputs or its result. The
 * result is [highlighted] in the theme's primary container colour.
 */
@Composable
fun SolverCard(title: String, highlighted: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) scheme.primaryContainer else scheme.surfaceContainerHigh,
            contentColor = if (highlighted) scheme.onPrimaryContainer else scheme.onSurface,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (highlighted) scheme.onPrimaryContainer else scheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/** A numbered input, as in "① Integrand h(t, x)". */
@Composable
fun StepLabel(number: Int, text: String) {
    Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
}

/** A short line in each colour with its meaning, one per row. */
@Composable
fun LegendRows(items: List<Pair<Color, String>>) {
    Column(Modifier.padding(top = 4.dp)) {
        for ((color, label) in items) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 18.dp, height = 10.dp)) {
                    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.5.dp.toPx())
                }
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The phone's decimal mark, e.g. a comma in Lithuanian. */
internal val separator: Char by lazy { DecimalFormatSymbols.getInstance().decimalSeparator }

/** TeX for a formula, with the phone's decimal mark. */
fun Expr.tex(): String = toTex(separator)

fun fmt(value: Double, decimals: Int = 3): String = Ticks.formatFixed(value, decimals, separator)

/** A number typed with either decimal separator. */
fun parseNumber(text: String): Double =
    text.trim().replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException("'$text' is not a number")
