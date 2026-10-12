package id.eraserremake.app.data

import android.content.Context
import id.eraserremake.app.ui.theme.ThemeMode

/** Preferensi aplikasi yang persisten (SharedPreferences, tanpa DataStore
 * agar dependensi tetap kecil). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = when (prefs.getString(KEY_THEME, null)) {
            "Light" -> ThemeMode.Light
            "Dark" -> ThemeMode.Dark
            else -> ThemeMode.System
        }
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
        }

    var useNnapi: Boolean
        get() = prefs.getBoolean(KEY_NNAPI, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NNAPI, value).apply()
        }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_NNAPI = "use_nnapi"
    }
}
