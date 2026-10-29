package com.example.agenticcar

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import com.example.agenticcar.ui.theme.ThemeMode
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.example.agenticcar.ui.board.AnalysisRoute
import com.example.agenticcar.ui.board.LiveBoardRoute
import com.example.agenticcar.ui.dashboard.DashboardRoute
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agenticcar.ui.theme.AgenticCarTheme
import com.example.agenticcar.ui.theme.AppearanceSettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appearanceSettings: AppearanceSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance by appearanceSettings.appearance.collectAsStateWithLifecycle()
            val dark = when (appearance.mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status and navigation bar icons follow the app's theme, not the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose { }
            }
            AgenticCarTheme(appearance) {
                AppNavigation()
            }
        }
    }
}

// The scrims enableEdgeToEdge uses by default for three-button navigation.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

private enum class Destination(val label: String, @param:DrawableRes val icon: Int) {
    LOGGER("Logger", R.drawable.ic_stat_obd),
    BOARD("Board", R.drawable.ic_nav_board),
    ANALYSIS("Analysis", R.drawable.ic_nav_analysis),
}

/**
 * Three top-level pages; a saveable selection is simpler than a navigation graph for flat tabs.
 * Landscape puts them in a side rail: on a phone held sideways a bottom bar would eat a third of the height.
 */
@Composable
private fun AppNavigation() {
    var current by rememberSaveable { mutableStateOf(Destination.LOGGER) }
    val page: @Composable () -> Unit = {
        when (current) {
            Destination.LOGGER -> DashboardRoute()
            Destination.BOARD -> LiveBoardRoute()
            Destination.ANALYSIS -> AnalysisRoute()
        }
    }
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            NavigationRail {
                Spacer(Modifier.weight(1f))
                Destination.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = current == destination,
                        onClick = { current = destination },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            // The rail already sits clear of the cutout and side navigation buttons on its edge.
            Box(Modifier.weight(1f).fillMaxHeight().consumeWindowInsets(WindowInsets.displayCutout.only(WindowInsetsSides.Start))) {
                page()
            }
        }
        return
    }
    Scaffold(
        // Each page's own Scaffold handles the status bar; this one only reserves the navigation bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = current == destination,
                        onClick = { current = destination },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            page()
        }
    }
}
