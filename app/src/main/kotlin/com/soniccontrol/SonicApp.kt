package com.soniccontrol

import android.app.Application
import com.soniccontrol.core.audio.AudioController
import com.soniccontrol.core.database.SonicDatabase
import com.soniccontrol.core.devices.DriverRegistry
import com.soniccontrol.core.diagnostics.DiagnosticLog
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
}

class SonicApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
