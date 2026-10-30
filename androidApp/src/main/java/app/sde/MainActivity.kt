package app.sde

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import app.sde.ui.AppRoot
import app.sde.ui.SdeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppSettings(this)
        // The manifest's window follows the phone; paint it in the app's own choice before Compose draws.
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.decorView.setBackgroundColor(if (settings.themeMode.isDark(systemDark)) DARK_BACKGROUND else LIGHT_BACKGROUND)
        enableEdgeToEdge()
        setContent {
            val dark = settings.themeMode.isDark(isSystemInDarkTheme())
            // Light status bar icons on a dark app and dark ones on a light app, whatever the phone's theme.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            SdeTheme(dark) { AppRoot(settings) }
        }
    }

    private companion object {
        val LIGHT_BACKGROUND = Color.rgb(0xFA, 0xFB, 0xFD)
        val DARK_BACKGROUND = Color.rgb(0x0F, 0x12, 0x17)

        // The scrims enableEdgeToEdge uses by default behind three-button navigation.
        val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
