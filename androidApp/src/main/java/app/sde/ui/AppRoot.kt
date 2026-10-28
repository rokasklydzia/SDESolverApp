package app.sde.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private val tabs = listOf("Simulate", "Labs", "Demos", "Engine")

@Composable
fun AppRoot() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TabRow(selectedTabIndex = selected) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = index == selected, onClick = { selected = index }, text = { Text(title) })
                }
            }
            when (selected) {
                0 -> SimulateScreen()
                1 -> LabsScreen()
                2 -> DemosScreen()
                else -> EngineScreen()
            }
        }
    }
}
