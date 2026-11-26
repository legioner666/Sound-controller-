package com.soniccontrol.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** TASK 012: "Battery notification" setting — default off, applied at once, kept across restarts and app updates. */
class BatteryNotificationSettingTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("sonic-settings").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun file() = File(dir, "sonic_control.preferences_pb")

    /** One "process": its own scope and DataStore instance over the same file as the app uses across launches. */
    private class Process(file: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val db = SonicDatabase(store, scope)
        suspend fun kill() = scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test fun defaultIsOffForANewInstall() {
        assertFalse(AppSettings().batteryNotification)
        assertFalse(decodeSettings(emptyPreferences()).batteryNotification)
    }

    @Test fun oldDefaultOnMonitoringValueIsNotCarriedOverOnUpdate() {
        val legacy = preferencesOf(booleanPreferencesKey("background_monitoring") to true)
        assertFalse(decodeSettings(legacy).batteryNotification)
    }

    @Test fun storedChoiceIsRead() {
        assertTrue(decodeSettings(preferencesOf(booleanPreferencesKey("battery_notification") to true)).batteryNotification)
        assertFalse(decodeSettings(preferencesOf(booleanPreferencesKey("battery_notification") to false)).batteryNotification)
    }

    @Test fun changeIsVisibleAtOnceAndSurvivesRestart() = runBlocking {
        withTimeout(10_000) {
            val first = Process(file())
            assertFalse(first.db.settings.first { true }.batteryNotification)
            first.db.setBatteryNotification(true)
            // Applied without a restart: the settings flow the background controller listens to has the new value.
            assertTrue(first.db.settings.first { it.batteryNotification }.batteryNotification)
            first.kill()

            val second = Process(file())   // app restarted / APK updated: same DataStore file
            assertTrue(second.store.data.first().let(::decodeSettings).batteryNotification)
            second.db.setBatteryNotification(false)
            second.kill()

            val third = Process(file())
            assertFalse(third.store.data.first().let(::decodeSettings).batteryNotification)
            third.kill()
        }
    }

    @Test fun writingTheNewSettingRemovesTheLegacyKey() = runBlocking {
        withTimeout(10_000) {
            val legacyKey = booleanPreferencesKey("background_monitoring")
            val p = Process(file())
            p.store.edit { it[legacyKey] = true }
            p.db.setBatteryNotification(false)
            val stored = p.store.data.first()
            assertNull(stored[legacyKey])
            assertEquals(false, stored[booleanPreferencesKey("battery_notification")])
            p.kill()
        }
    }
}
