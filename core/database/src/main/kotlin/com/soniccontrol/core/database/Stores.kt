package com.soniccontrol.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
    /**
     * Optional ongoing notification with the headset's battery (TASK 012). OFF by default: battery, charging and earbud
     * activity are shown on the main screen. When on, Android's required foreground-service notification is that notification.
     */
    val batteryNotification: Boolean = DEFAULT_BATTERY_NOTIFICATION,
    val lastDevice: String? = null,
    val onboardingDone: Boolean = false,
    /** Phone (software) equaliser, independent of the headphones. */
    val systemEq: Boolean = false,
    val eqProfileId: String = "flat",
    /** -1 left … +1 right. */
    val balance: Float = 0f,
    /** Headset refresh interval while the main screen is open (another section uses twice this). One of [REFRESH_ACTIVE_CHOICES]. */
    val refreshActiveSeconds: Int = 5,
    /** Headset refresh interval in the background and with the screen locked. One of [REFRESH_BACKGROUND_CHOICES]. */
    val refreshBackgroundSeconds: Int = 60,
) {
    companion object {
        const val DEFAULT_BATTERY_NOTIFICATION = false
        val REFRESH_ACTIVE_CHOICES = listOf(5, 10, 15, 30)
        val REFRESH_BACKGROUND_CHOICES = listOf(30, 60, 120, 300)
    }
}

/**
 * All persistent data, on the phone only (DataStore). No accounts, no cloud, no analytics.
 * Values are small; structured items use the line formats in [Codecs].
 */
class SonicDatabase internal constructor(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    constructor(context: Context, scope: CoroutineScope) : this(context.store, scope)

    internal object K {
        val theme = stringPreferencesKey("theme")
        val experimental = booleanPreferencesKey("experimental")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val fastPair = booleanPreferencesKey("fast_pair_listen")
        /** New key (TASK 012) so the old default-on "background monitoring" value is not carried over as a choice. */
        val batteryNotification = booleanPreferencesKey("battery_notification")
        /** Pre-TASK 012 key; ignored when reading and removed on the first write of the new setting. */
        val legacyBackgroundMonitoring = booleanPreferencesKey("background_monitoring")
        val lastDevice = stringPreferencesKey("last_device")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val eqProfiles = stringPreferencesKey("eq_profiles")
        val batteryHistory = stringPreferencesKey("battery_history")
        val salt = stringPreferencesKey("anon_salt")
        val systemEq = booleanPreferencesKey("system_eq")
        val eqProfile = stringPreferencesKey("eq_profile_id")
        val balance = floatPreferencesKey("balance")
        val refreshActive = intPreferencesKey("refresh_active_s")
        val refreshBackground = intPreferencesKey("refresh_background_s")
        /** TASK 014: automation rules (RuleCodec text in the app) and touch-control backups per headset. */
        val automationRules = stringPreferencesKey("automation_rules")
        val gestureBackups = stringPreferencesKey("gesture_backups")
    }

    val settings: StateFlow<AppSettings> = store.data.map(::decodeSettings).stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val eqProfiles: StateFlow<List<EqProfile>> = store.data.map { p ->
        Codecs.decodeProfiles(p[K.eqProfiles]).ifEmpty { EqProfile.PRESETS }
    }.stateIn(scope, SharingStarted.Eagerly, EqProfile.PRESETS)

    val batteryHistory: StateFlow<List<BatteryRecord>> = store.data.map { p ->
        Codecs.decodeHistory(p[K.batteryHistory])
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Rules as text (encoded/decoded by the automation module, so this module needs no knowledge of them). */
    val automationRules: StateFlow<String> = store.data.map { it[K.automationRules].orEmpty() }.stateIn(scope, SharingStarted.Eagerly, "")
    suspend fun saveAutomationRules(text: String) = store.edit { it[K.automationRules] = text }

    val gestureBackups: StateFlow<Map<String, com.soniccontrol.core.devices.GestureBackup>> =
        store.data.map { Codecs.decodeBackups(it[K.gestureBackups]) }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    suspend fun saveGestureBackup(deviceKey: String, b: com.soniccontrol.core.devices.GestureBackup) =
        store.edit { it[K.gestureBackups] = Codecs.encodeBackups(Codecs.decodeBackups(it[K.gestureBackups]) + (deviceKey to b)) }
    suspend fun deleteGestureBackup(deviceKey: String) =
        store.edit { it[K.gestureBackups] = Codecs.encodeBackups(Codecs.decodeBackups(it[K.gestureBackups]) - deviceKey) }

    suspend fun setTheme(t: ThemeMode) = store.edit { it[K.theme] = t.name }
    suspend fun setExperimental(v: Boolean) = store.edit { it[K.experimental] = v }
    suspend fun setAutoReconnect(v: Boolean) = store.edit { it[K.autoReconnect] = v }
    suspend fun setFastPairListening(v: Boolean) = store.edit { it[K.fastPair] = v }
    /** Applied at once (the settings flow emits on commit) and kept across restarts and app updates (DataStore file). */
    suspend fun setBatteryNotification(v: Boolean) = store.edit { it[K.batteryNotification] = v; it.remove(K.legacyBackgroundMonitoring) }
    suspend fun setRefreshActiveSeconds(v: Int) { if (v in AppSettings.REFRESH_ACTIVE_CHOICES) store.edit { it[K.refreshActive] = v } }
    suspend fun setRefreshBackgroundSeconds(v: Int) { if (v in AppSettings.REFRESH_BACKGROUND_CHOICES) store.edit { it[K.refreshBackground] = v } }
    suspend fun setLastDevice(address: String?) = store.edit { if (address == null) it.remove(K.lastDevice) else it[K.lastDevice] = address }
    suspend fun setOnboardingDone() = store.edit { it[K.onboarding] = true }
    suspend fun setSystemEq(v: Boolean) = store.edit { it[K.systemEq] = v }
    suspend fun setEqProfile(id: String) = store.edit { it[K.eqProfile] = id }
    suspend fun setBalance(v: Float) = store.edit { it[K.balance] = v.coerceIn(-1f, 1f) }

    suspend fun saveProfiles(list: List<EqProfile>) = store.edit { it[K.eqProfiles] = Codecs.encodeProfiles(list) }

    /** Appends a real reading (never synthetic). Keeps the most recent [BatteryHistory.MAX_RECORDS]. */
    suspend fun recordBattery(deviceKey: String, status: BatteryStatus) {
        val rec = BatteryRecord.from(deviceKey, status) ?: return
        store.edit { p ->
            val list = Codecs.decodeHistory(p[K.batteryHistory])
            if (!BatteryHistory.shouldAppend(list.lastOrNull { it.deviceKey == deviceKey }, rec)) return@edit
            p[K.batteryHistory] = Codecs.encodeHistory((list + rec).takeLast(BatteryHistory.MAX_RECORDS))
        }
    }

    suspend fun clearBatteryHistory() = store.edit { it.remove(K.batteryHistory) }

    /** Per-install random salt for anonymising diagnostics. */
    suspend fun anonymizationSalt(): String {
        var salt: String? = null
        store.edit { p -> salt = p[K.salt] ?: UUID.randomUUID().toString().also { p[K.salt] = it } }
        return salt!!
    }
}

/** Pure mapping from stored preferences to settings; unknown or invalid values fall back to the defaults. */
internal fun decodeSettings(p: Preferences): AppSettings =
    AppSettings(
        theme = p[SonicDatabase.K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        experimental = p[SonicDatabase.K.experimental] ?: false,
        autoReconnect = p[SonicDatabase.K.autoReconnect] ?: true,
        fastPairListening = p[SonicDatabase.K.fastPair] ?: true,
        batteryNotification = p[SonicDatabase.K.batteryNotification] ?: AppSettings.DEFAULT_BATTERY_NOTIFICATION,
        lastDevice = p[SonicDatabase.K.lastDevice],
        onboardingDone = p[SonicDatabase.K.onboarding] ?: false,
        systemEq = p[SonicDatabase.K.systemEq] ?: false,
        eqProfileId = p[SonicDatabase.K.eqProfile] ?: "flat",
        balance = (p[SonicDatabase.K.balance] ?: 0f).coerceIn(-1f, 1f),
        refreshActiveSeconds = p[SonicDatabase.K.refreshActive]?.takeIf { it in AppSettings.REFRESH_ACTIVE_CHOICES } ?: 5,
        refreshBackgroundSeconds = p[SonicDatabase.K.refreshBackground]?.takeIf { it in AppSettings.REFRESH_BACKGROUND_CHOICES } ?: 60,
    )
