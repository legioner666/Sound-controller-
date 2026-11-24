package com.soniccontrol.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.soniccontrol.core.devices.BatteryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "sonic_control")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val experimental: Boolean = false,
    val autoReconnect: Boolean = true,
    val fastPairListening: Boolean = true,
    val lastDevice: String? = null,
    val onboardingDone: Boolean = false,
    /** Phone (software) equaliser, independent of the headphones. */
    val systemEq: Boolean = false,
    val eqProfileId: String = "flat",
    /** -1 left … +1 right. */
    val balance: Float = 0f,
)

/**
 * All persistent data, on the phone only (DataStore). No accounts, no cloud, no analytics.
 * Values are small; structured items use the line formats in [Codecs].
 */
class SonicDatabase(private val context: Context, scope: CoroutineScope) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val experimental = booleanPreferencesKey("experimental")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val fastPair = booleanPreferencesKey("fast_pair_listen")
        val lastDevice = stringPreferencesKey("last_device")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val eqProfiles = stringPreferencesKey("eq_profiles")
        val batteryHistory = stringPreferencesKey("battery_history")
        val salt = stringPreferencesKey("anon_salt")
        val systemEq = booleanPreferencesKey("system_eq")
        val eqProfile = stringPreferencesKey("eq_profile_id")
        val balance = floatPreferencesKey("balance")
    }

    val settings: StateFlow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            experimental = p[K.experimental] ?: false,
            autoReconnect = p[K.autoReconnect] ?: true,
            fastPairListening = p[K.fastPair] ?: true,
            lastDevice = p[K.lastDevice],
            onboardingDone = p[K.onboarding] ?: false,
            systemEq = p[K.systemEq] ?: false,
            eqProfileId = p[K.eqProfile] ?: "flat",
            balance = (p[K.balance] ?: 0f).coerceIn(-1f, 1f),
        )
    }.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val eqProfiles: StateFlow<List<EqProfile>> = context.store.data.map { p ->
        Codecs.decodeProfiles(p[K.eqProfiles]).ifEmpty { EqProfile.PRESETS }
    }.stateIn(scope, SharingStarted.Eagerly, EqProfile.PRESETS)

    val batteryHistory: StateFlow<List<BatteryRecord>> = context.store.data.map { p ->
        Codecs.decodeHistory(p[K.batteryHistory])
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun setTheme(t: ThemeMode) = context.store.edit { it[K.theme] = t.name }
    suspend fun setExperimental(v: Boolean) = context.store.edit { it[K.experimental] = v }
    suspend fun setAutoReconnect(v: Boolean) = context.store.edit { it[K.autoReconnect] = v }
    suspend fun setFastPairListening(v: Boolean) = context.store.edit { it[K.fastPair] = v }
    suspend fun setLastDevice(address: String?) = context.store.edit { if (address == null) it.remove(K.lastDevice) else it[K.lastDevice] = address }
    suspend fun setOnboardingDone() = context.store.edit { it[K.onboarding] = true }
    suspend fun setSystemEq(v: Boolean) = context.store.edit { it[K.systemEq] = v }
    suspend fun setEqProfile(id: String) = context.store.edit { it[K.eqProfile] = id }
    suspend fun setBalance(v: Float) = context.store.edit { it[K.balance] = v.coerceIn(-1f, 1f) }

    suspend fun saveProfiles(list: List<EqProfile>) = context.store.edit { it[K.eqProfiles] = Codecs.encodeProfiles(list) }

    /** Appends a real reading (never synthetic). Keeps the most recent [BatteryHistory.MAX_RECORDS]. */
    suspend fun recordBattery(deviceKey: String, status: BatteryStatus) {
        val rec = BatteryRecord.from(deviceKey, status) ?: return
        context.store.edit { p ->
            val list = Codecs.decodeHistory(p[K.batteryHistory])
            if (!BatteryHistory.shouldAppend(list.lastOrNull { it.deviceKey == deviceKey }, rec)) return@edit
            p[K.batteryHistory] = Codecs.encodeHistory((list + rec).takeLast(BatteryHistory.MAX_RECORDS))
        }
    }

    suspend fun clearBatteryHistory() = context.store.edit { it.remove(K.batteryHistory) }

    /** Per-install random salt for anonymising diagnostics. */
    suspend fun anonymizationSalt(): String {
        var salt: String? = null
        context.store.edit { p -> salt = p[K.salt] ?: UUID.randomUUID().toString().also { p[K.salt] = it } }
        return salt!!
    }
}
