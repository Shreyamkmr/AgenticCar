package com.example.agenticcar.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

@Composable
fun AgenticCarTheme(
    appearance: Appearance = Appearance(),
    content: @Composable () -> Unit,
) {
    val dark = when (appearance.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val wallpaper = appearance.palette == Palette.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val base = when {
        wallpaper -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> paletteScheme(appearance.palette, dark, appearance.vibrant)
    }
    val scheme = if (dark && appearance.pureBlack) base.blackened() else base

    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
}

/**
 * A full Material 3 scheme from a palette's three seed colours: each role is the seed's hue at the
 * lightness Material uses for that role. Surfaces carry a hint of the primary hue (more when vibrant).
 */
fun paletteScheme(palette: Palette, dark: Boolean, vibrant: Boolean): ColorScheme {
    val accentSat = if (vibrant) 1f else 0.55f
    val surfaceSat = if (vibrant) 0.32f else 0.1f
    fun Color.tone(lightness: Float, saturation: Float = accentSat): Color {
        val (h, sat, _) = toHsl()
        return hsl(h, (sat * saturation).coerceIn(0f, 1f), lightness)
    }

    /**
     * An accent at [lightness], nudged darker (light theme) or lighter (dark theme) until text in
     * [on] reaches 4.5:1 contrast: bright hues such as teal or yellow need it, deep ones do not.
     */
    fun Color.accent(lightness: Float, on: Color): Color {
        var l = lightness
        var c = tone(l)
        while (contrast(c, on) < MIN_TEXT_CONTRAST && l in 0.05f..0.95f) {
            l += if (dark) 0.02f else -0.02f
            c = tone(l)
        }
        return c
    }
    val p = palette.primary
    val s = palette.secondary
    val t = palette.tertiary
    fun surface(lightness: Float) = p.tone(lightness, surfaceSat)

    return if (dark) {
        darkColorScheme(
            primary = p.accent(0.72f, p.tone(0.14f)), onPrimary = p.tone(0.14f),
            primaryContainer = p.tone(0.28f), onPrimaryContainer = p.tone(0.90f),
            secondary = s.accent(0.74f, s.tone(0.14f)), onSecondary = s.tone(0.14f),
            secondaryContainer = s.tone(0.26f), onSecondaryContainer = s.tone(0.90f),
            tertiary = t.accent(0.72f, t.tone(0.14f)), onTertiary = t.tone(0.14f),
            tertiaryContainer = t.tone(0.28f), onTertiaryContainer = t.tone(0.90f),
            background = surface(0.07f), onBackground = surface(0.92f),
            surface = surface(0.07f), onSurface = surface(0.92f),
            surfaceVariant = surface(0.24f), onSurfaceVariant = surface(0.78f),
            surfaceDim = surface(0.07f), surfaceBright = surface(0.24f),
            surfaceContainerLowest = surface(0.04f), surfaceContainerLow = surface(0.10f),
            surfaceContainer = surface(0.12f), surfaceContainerHigh = surface(0.16f),
            surfaceContainerHighest = surface(0.21f),
            outline = surface(0.55f), outlineVariant = surface(0.28f),
            inverseSurface = surface(0.90f), inverseOnSurface = surface(0.15f), inversePrimary = p.tone(0.40f),
        )
    } else {
        lightColorScheme(
            primary = p.accent(0.40f, Color.White), onPrimary = Color.White,
            primaryContainer = p.tone(0.88f), onPrimaryContainer = p.tone(0.12f),
            secondary = s.accent(0.38f, Color.White), onSecondary = Color.White,
            secondaryContainer = s.tone(0.88f), onSecondaryContainer = s.tone(0.12f),
            tertiary = t.accent(0.40f, Color.White), onTertiary = Color.White,
            tertiaryContainer = t.tone(0.88f), onTertiaryContainer = t.tone(0.12f),
            background = surface(0.98f), onBackground = surface(0.10f),
            surface = surface(0.98f), onSurface = surface(0.10f),
            surfaceVariant = surface(0.90f), onSurfaceVariant = surface(0.30f),
            surfaceDim = surface(0.87f), surfaceBright = surface(0.98f),
            surfaceContainerLowest = Color.White, surfaceContainerLow = surface(0.96f),
            surfaceContainer = surface(0.94f), surfaceContainerHigh = surface(0.92f),
            surfaceContainerHighest = surface(0.90f),
            outline = surface(0.50f), outlineVariant = surface(0.80f),
            inverseSurface = surface(0.18f), inverseOnSurface = surface(0.95f), inversePrimary = p.tone(0.80f),
        )
    }
}

/** WCAG AA for normal text. */
private const val MIN_TEXT_CONTRAST = 4.5f

/** WCAG contrast ratio between two colours, 1..21. */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return maxOf(la, lb) / minOf(la, lb)
}

/** Hue (degrees), saturation and lightness (0..1) of an sRGB colour. */
private fun Color.toHsl(): Triple<Float, Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val l = (max + min) / 2
    if (max == min) return Triple(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
    val h = when (max) {
        red -> (green - blue) / d + if (green < blue) 6 else 0
        green -> (blue - red) / d + 2
        else -> (red - green) / d + 4
    } * 60f
    return Triple(h, s, l)
}

private fun hsl(h: Float, s: Float, l: Float): Color {
    if (s == 0f) return Color(l, l, l)
    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    fun channel(offset: Float): Float {
        var t = h / 360f + offset
        if (t < 0) t += 1
        if (t > 1) t -= 1
        return when {
            t < 1f / 6 -> p + (q - p) * 6 * t
            t < 1f / 2 -> q
            t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
            else -> p
        }.coerceIn(0f, 1f)
    }
    return Color(channel(1f / 3), channel(0f), channel(-1f / 3))
}

/** Pure black background with containers stepping up from it, for OLED screens at night. */
private fun ColorScheme.blackened(): ColorScheme {
    fun lift(c: Color, amount: Float) = lerp(Color.Black, c, amount)
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = lift(surfaceContainerLow, 0.6f),
        surfaceContainer = lift(surfaceContainer, 0.7f),
        surfaceContainerHigh = lift(surfaceContainerHigh, 0.75f),
        surfaceContainerHighest = lift(surfaceContainerHighest, 0.8f),
    )
}
