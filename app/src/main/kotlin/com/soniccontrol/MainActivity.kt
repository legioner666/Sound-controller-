package com.soniccontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.soniccontrol.core.bluetooth.BluetoothPermissions
import com.soniccontrol.core.database.ThemeMode
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.SupportStatus
import kotlinx.coroutines.launch
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SegmentedTabs
import com.soniccontrol.core.designsystem.SonicTheme
import com.soniccontrol.feature.automation.AutomationScreen
import com.soniccontrol.feature.battery.BatteryScreen
import com.soniccontrol.feature.dashboard.DashboardScreen
import com.soniccontrol.feature.devices.DevicesScreen
import com.soniccontrol.feature.diagnostics.DiagnosticsScreen
import com.soniccontrol.feature.equalizer.EqualizerScreen
import com.soniccontrol.feature.gestures.GesturesScreen
import com.soniccontrol.feature.noisecontrol.NoiseControlScreen
import com.soniccontrol.feature.settings.SettingsScreen
import com.soniccontrol.feature.player.MiniPlayer
import com.soniccontrol.feature.player.MusicScreen
import com.soniccontrol.feature.player.NowPlayingScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.soniccontrol.core.background.AppShutdown.clear(this)   // a manual open ends the post-timer shutdown
        enableEdgeToEdge()
        val container = (application as SonicApp).container
        lifecycleScope.launch { com.soniccontrol.core.background.AppShutdown.closeUi.collect { finishAndRemoveTask() } }
        setContent {
            val settings by container.db.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) { ThemeMode.SYSTEM -> null; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
            SonicTheme(dark = dark) { SonicNavigation(container, showOnboarding = !settings.onboardingDone) }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as SonicApp).container.controller.refreshAvailability()
        (application as SonicApp).container.audio.refreshVolume()
    }
}

private enum class NavTab(val route: String, val label: Int, val icon: ImageVector) {
    HOME("home", R.string.tab_home, Icons.Outlined.Home),
    DEVICES("devices", R.string.tab_devices, Icons.Outlined.Headphones),
    MUSIC("music", R.string.tab_music, Icons.Outlined.MusicNote),
    SOUND("sound", R.string.tab_sound, Icons.Outlined.GraphicEq),
    LAB("lab", R.string.tab_lab, Icons.Outlined.Science),
    SETTINGS("settings", R.string.tab_settings, Icons.Outlined.Settings),
}

private const val PLAYER_ROUTE = "player"

private enum class SoundPage { NOISE, EQ, GESTURES, AUTOMATION }
private enum class LabPage { DIAGNOSTICS, BATTERY }

@Composable
private fun SonicNavigation(container: AppContainer, showOnboarding: Boolean) {
    val nav = rememberNavController()
    val controller = container.controller
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { controller.refreshAvailability() }
    val requestPermissions = { permissionLauncher.launch(BluetoothPermissions.required) }

    LaunchedEffect(Unit) {
        controller.results.collect { r ->
            if (r.action == "refresh" && r.outcome is com.soniccontrol.core.devices.CommandOutcome.Confirmed) return@collect
            snackbar.showSnackbar(outcomeMessage(context, r.outcome))
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val scope = rememberCoroutineScope()

    if (showOnboarding) {
        OnboardingFlow(controller, onRequestPermissions = requestPermissions) { scope.launch { container.db.setOnboardingDone() } }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
          Column {
            val playerState by container.player.state.collectAsStateWithLifecycle()
            if (playerState.current != null && current != PLAYER_ROUTE) MiniPlayer(container.player, onOpen = { nav.navigate(PLAYER_ROUTE) { launchSingleTop = true } })
            NavigationBar {
                NavTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) },
                    )
                }
            }
          }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            NavHost(nav, startDestination = NavTab.HOME.route) {
                composable(NavTab.HOME.route) {
                    DashboardScreen(
                        controller, controller, container.audio, container.db,
                        onOpenDevices = { nav.navigate(NavTab.DEVICES.route) },
                        onOpenEqualizer = { nav.navigate(NavTab.SOUND.route) },
                        onOpenLab = { nav.navigate(NavTab.LAB.route) },
                        onRequestPermissions = requestPermissions,
                    )
                }
                composable(NavTab.MUSIC.route) {
                    MusicScreen(container.library, container.player, onOpenPlayer = { nav.navigate(PLAYER_ROUTE) { launchSingleTop = true } })
                }
                composable(PLAYER_ROUTE) { NowPlayingScreen(container.player, controller, onClose = { nav.popBackStack() }) }
                composable(NavTab.DEVICES.route) { DevicesScreen(controller, onRequestPermissions = requestPermissions) }
                composable(NavTab.SOUND.route) {
                    val active by controller.active.collectAsStateWithLifecycle()
                    // Pages for features the connected headset cannot use at all are hidden.
                    val noiseUnsupported = active?.headphone?.let {
                        it.noiseModes.isEmpty() && it.capabilities.of(Feature.NOISE_CONTROL).status == SupportStatus.UNSUPPORTED
                    } ?: false
                    val pages = SoundPage.entries.filter { it != SoundPage.NOISE || !noiseUnsupported }
                    var chosen by rememberSaveable { mutableStateOf(SoundPage.NOISE) }
                    val page = if (chosen in pages) chosen else pages.first()
                    Column {
                        PrimaryScrollableTabRow(selectedTabIndex = pages.indexOf(page), edgePadding = 16.dp) {
                            pages.forEach { p ->
                                Tab(selected = p == page, onClick = { chosen = p }, text = {
                                    Text(stringResource(when (p) {
                                        SoundPage.NOISE -> R.string.sound_page_noise; SoundPage.EQ -> R.string.sound_page_eq
                                        SoundPage.GESTURES -> R.string.sound_page_gestures; SoundPage.AUTOMATION -> R.string.sound_page_automation
                                    }))
                                })
                            }
                        }
                        when (page) {
                            SoundPage.NOISE -> NoiseControlScreen(controller, controller.experimental)
                            SoundPage.EQ -> EqualizerScreen(controller, container.db, container.systemEq)
                            SoundPage.GESTURES -> GesturesScreen(controller)
                            SoundPage.AUTOMATION -> AutomationScreen(container.audio)
                        }
                    }
                }
                composable(NavTab.LAB.route) {
                    var page by rememberSaveable { mutableStateOf(LabPage.DIAGNOSTICS) }
                    Column {
                        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                            SegmentedTabs(LabPage.entries, page, {
                                stringResource(if (it == LabPage.DIAGNOSTICS) R.string.lab_page_diagnostics else R.string.lab_page_battery)
                            }) { page = it }
                        }
                        when (page) {
                            LabPage.DIAGNOSTICS -> DiagnosticsScreen(controller, controller, container.log, controller.experimental)
                            LabPage.BATTERY -> BatteryScreen(controller, container.db) { it }
                        }
                    }
                }
                composable(NavTab.SETTINGS.route) { SettingsScreen(container.db, BuildConfig.VERSION_NAME) }
            }
        }
    }
}

private fun outcomeMessage(context: android.content.Context, o: com.soniccontrol.core.devices.CommandOutcome): String {
    val r = context.resources
    return when (o) {
        is com.soniccontrol.core.devices.CommandOutcome.Confirmed -> r.getString(R.string.outcome_confirmed)
        com.soniccontrol.core.devices.CommandOutcome.SentUnconfirmed -> r.getString(R.string.outcome_unconfirmed)
        is com.soniccontrol.core.devices.CommandOutcome.Rejected -> r.getString(R.string.outcome_rejected)
        com.soniccontrol.core.devices.CommandOutcome.Timeout -> r.getString(R.string.outcome_timeout)
        com.soniccontrol.core.devices.CommandOutcome.NotConnected -> r.getString(R.string.outcome_not_connected)
        is com.soniccontrol.core.devices.CommandOutcome.Unsupported -> r.getString(R.string.outcome_unsupported)
        is com.soniccontrol.core.devices.CommandOutcome.Failed -> r.getString(R.string.outcome_failed)
    }
}
