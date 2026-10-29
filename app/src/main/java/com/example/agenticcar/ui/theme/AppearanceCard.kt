package com.example.agenticcar.ui.theme

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Theme mode, colour palette and the vibrant / pure-black switches; every change applies at once. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceCard(appearance: Appearance, onChange: ((Appearance) -> Appearance) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = appearance.mode == mode,
                        onClick = { onChange { it.copy(mode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(mode.label) }
                }
            }

            Text("Colours", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Material You needs Android 12.
                Palette.entries.filter { it != Palette.WALLPAPER || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }.forEach { palette ->
                    Swatch(palette, selected = appearance.palette == palette) { onChange { it.copy(palette = palette) } }
                }
            }

            SwitchRow("Vibrant colours", "Saturated accents and tinted backgrounds", appearance.vibrant) { on ->
                onChange { it.copy(vibrant = on) }
            }
            SwitchRow("Pure black in dark mode", "Deepest blacks, easier on OLED screens at night", appearance.pureBlack) { on ->
                onChange { it.copy(pureBlack = on) }
            }
        }
    }
}

@Composable
private fun Swatch(palette: Palette, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .width(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .size(40.dp)
                .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
                .padding(4.dp)
                .clip(CircleShape),
        ) {
            if (palette == Palette.WALLPAPER) {
                // Material You picks from the wallpaper: shown as a soft rainbow.
                drawCircle(
                    androidx.compose.ui.graphics.Brush.sweepGradient(
                        listOf(palette.primary, palette.tertiary, palette.secondary, palette.primary),
                    ),
                )
            } else {
                // Primary on the top half, secondary and tertiary below.
                drawRect(palette.primary, Offset.Zero, Size(size.width, size.height / 2))
                drawRect(palette.secondary, Offset(0f, size.height / 2), Size(size.width / 2, size.height / 2))
                drawRect(palette.tertiary, Offset(size.width / 2, size.height / 2), Size(size.width / 2, size.height / 2))
            }
        }
        Text(palette.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
