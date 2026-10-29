package com.example.agenticcar.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Colour sets, each from three seeds: primary, secondary and tertiary. [WALLPAPER] uses Material You. */
enum class Palette(val label: String, val primary: Color, val secondary: Color, val tertiary: Color) {
    AGENTIC("Agentic", Color(0xFF14B8A6), Color(0xFF38BDF8), Color(0xFFFBBF24)),
    IGNITION("Ignition", Color(0xFFFF6B2C), Color(0xFFFFB020), Color(0xFFE5304B)),
    OCEAN("Ocean", Color(0xFF1E88E5), Color(0xFF26C6DA), Color(0xFF5E60CE)),
    ELECTRIC("Electric", Color(0xFF7C4DFF), Color(0xFFE040FB), Color(0xFF00E5FF)),
    RACING("Racing", Color(0xFF16A34A), Color(0xFFA3E635), Color(0xFFF2C14E)),
    GRAPHITE("Graphite", Color(0xFF64748B), Color(0xFF94A3B8), Color(0xFFFF6B2C)),
    WALLPAPER("Wallpaper", Color(0xFF6750A4), Color(0xFF625B71), Color(0xFF7D5260)),
}

data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val palette: Palette = Palette.AGENTIC,
    /** Saturated accents and tinted surfaces; off gives a calmer, greyer look. */
    val vibrant: Boolean = true,
    /** True black backgrounds in dark mode, easy on OLED screens at night. */
    val pureBlack: Boolean = false,
)

@Singleton
class AppearanceSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _appearance = MutableStateFlow(
        Appearance(
            mode = enumOr(prefs.getString(KEY_MODE, null), ThemeMode.SYSTEM),
            palette = enumOr(prefs.getString(KEY_PALETTE, null), Palette.AGENTIC),
            vibrant = prefs.getBoolean(KEY_VIBRANT, true),
            pureBlack = prefs.getBoolean(KEY_PURE_BLACK, false),
        ),
    )
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun update(change: (Appearance) -> Appearance) {
        val next = change(_appearance.value)
        _appearance.value = next
        prefs.edit()
            .putString(KEY_MODE, next.mode.name)
            .putString(KEY_PALETTE, next.palette.name)
            .putBoolean(KEY_VIBRANT, next.vibrant)
            .putBoolean(KEY_PURE_BLACK, next.pureBlack)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private companion object {
        const val PREFS = "appearance"
        const val KEY_MODE = "mode"
        const val KEY_PALETTE = "palette"
        const val KEY_VIBRANT = "vibrant"
        const val KEY_PURE_BLACK = "pure_black"
    }
}

@HiltViewModel
class AppearanceViewModel @Inject constructor(private val settings: AppearanceSettings) : ViewModel() {
    val appearance: StateFlow<Appearance> = settings.appearance

    fun update(change: (Appearance) -> Appearance) = settings.update(change)
}
