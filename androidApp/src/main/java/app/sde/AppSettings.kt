package app.sde

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val label: String) {
    SYSTEM("Same as the phone"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

/** The few choices the app remembers between launches, as Compose state. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private var _themeMode by mutableStateOf(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(THEME, null) } ?: ThemeMode.SYSTEM,
    )
    var themeMode: ThemeMode
        get() = _themeMode
        set(value) {
            _themeMode = value
            prefs.edit().putString(THEME, value.name).apply()
        }

    private var _introSeen by mutableStateOf(prefs.getBoolean(INTRO_SEEN, false))
    var introSeen: Boolean
        get() = _introSeen
        set(value) {
            _introSeen = value
            prefs.edit().putBoolean(INTRO_SEEN, value).apply()
        }

    companion object {
        private const val THEME = "theme"

        /** Also set by the screenshot tour, which starts past the intro. */
        const val INTRO_SEEN = "intro_seen"
        const val PREFERENCES = "settings"
    }
}
