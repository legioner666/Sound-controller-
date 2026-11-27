package com.soniccontrol

import android.app.Application
import com.soniccontrol.core.audio.AudioController
import com.soniccontrol.core.audio.SystemEqualizer
import com.soniccontrol.core.database.SonicDatabase
import com.soniccontrol.core.devices.DriverRegistry
import com.soniccontrol.core.diagnostics.DiagnosticLog
import com.soniccontrol.core.player.LibraryController
import com.soniccontrol.core.player.PlayerConnection
import com.soniccontrol.drivers.apple.AppleDriver
import com.soniccontrol.drivers.bose.BoseDriver
import com.soniccontrol.drivers.generic.GenericDriver
import com.soniccontrol.drivers.generic.OtherBrands
import com.soniccontrol.drivers.jbl.JblDriver
import com.soniccontrol.drivers.nothing.NothingDriver
import com.soniccontrol.drivers.samsung.SamsungDriver
import com.soniccontrol.drivers.sony.SonyDriver
import com.soniccontrol.drivers.soundcore.SoundcoreDriver
import com.soniccontrol.drivers.xiaomi.XiaomiDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Manual dependency container: no reflection, no DI framework. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val log = DiagnosticLog()
    val db = SonicDatabase(app, scope)
    val audio = AudioController(app, scope)

    val registry = DriverRegistry(
        drivers = listOf(
            JblDriver(), SonyDriver(), SamsungDriver(), AppleDriver(), SoundcoreDriver(), BoseDriver(), XiaomiDriver(), NothingDriver(),
        ) + OtherBrands.all(),
        fallback = GenericDriver(),
    )

    val controller = SonicController(app, scope, db, log, registry, BuildConfig.VERSION_NAME)

    val systemEq = SystemEqualizer()

    val library = LibraryController(app, scope)
    val player = PlayerConnection(app)
    val backgroundMonitoring = BackgroundMonitoring(app, scope, db, controller)
    /** TASK 014: rules engine and Lab tools; both only use the existing sessions (no extra Bluetooth connections). */
    val automation = AutomationRuntime(scope, controller, player, db)
    val lab = LabRuntime(scope, controller, log, registry, automation, BuildConfig.VERSION_NAME)

    init {
        backgroundMonitoring.start()
        automation.start()
        lab.start()
        // Sleep timer ended: stop Bluetooth sessions and the device service; nothing restarts until the app is opened by hand.
        scope.launch {
            com.soniccontrol.core.background.AppShutdown.suppressed.collect { off ->
                if (off) {
                    controller.shutdown()
                    com.soniccontrol.core.background.DeviceMonitorService.stop(app)
                    (app.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancelAll()
                }
            }
        }
        // A saved queue shows up in the mini player (paused) without starting any music.
        scope.launch { if (library.repository.data.value.queue != null) player.connect() }
        // Keep the phone equaliser in step with the saved settings and the selected profile.
        scope.launch {
            combine(db.settings, db.eqProfiles) { s, profiles -> Triple(s, profiles.firstOrNull { it.id == s.eqProfileId } ?: profiles.first(), s.balance) }
                .collect { (s, profile, balance) ->
                    systemEq.setGains(profile.gainsDb)
                    systemEq.setBalance(balance)
                    if (systemEq.status.value.enabled != s.systemEq) systemEq.setEnabled(s.systemEq)
                }
        }
    }
}

class SonicApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.soniccontrol.core.background.AppShutdown.init(this)
        container = AppContainer(this)
    }
}
