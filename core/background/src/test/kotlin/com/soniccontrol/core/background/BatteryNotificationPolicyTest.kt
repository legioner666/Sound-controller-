package com.soniccontrol.core.background

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatteryNotificationPolicyTest {

    @Test fun offByDefault() {
        assertFalse(BatteryNotificationPolicy.DEFAULT_ENABLED)
        assertFalse(BatteryNotificationPolicy.shouldRun(BatteryNotificationPolicy.DEFAULT_ENABLED, hasDevice = true, suppressed = false, permitted = true))
    }

    @Test fun runsOnlyWhenEveryConditionHolds() {
        for (enabled in listOf(false, true)) for (has in listOf(false, true)) for (off in listOf(false, true)) for (perm in listOf(false, true)) {
            val expected = enabled && has && !off && perm
            assertEquals("enabled=$enabled has=$has suppressed=$off permitted=$perm", expected, BatteryNotificationPolicy.shouldRun(enabled, has, off, perm))
        }
    }

    @Test fun reconnectsNeverBringTheNotificationBackWhileOff() = runTest(UnconfinedTestDispatcher()) {
        val enabled = MutableStateFlow(false); val has = MutableStateFlow(false); val suppressed = MutableStateFlow(false)
        val out = mutableListOf<Boolean>()
        val job = launch { BatteryNotificationPolicy.desired(enabled, has, suppressed).toList(out) }
        repeat(5) { has.value = true; has.value = false }   // headset connects and drops five times
        has.value = true
        job.cancel()
        assertEquals(listOf(false), out)
    }

    @Test fun settingChangeAppliesImmediately() = runTest(UnconfinedTestDispatcher()) {
        val enabled = MutableStateFlow(false); val has = MutableStateFlow(true); val suppressed = MutableStateFlow(false)
        val out = mutableListOf<Boolean>()
        val job = launch { BatteryNotificationPolicy.desired(enabled, has, suppressed).toList(out) }
        enabled.value = true
        assertEquals(listOf(false, true), out)
        enabled.value = false
        assertEquals(listOf(false, true, false), out)
        job.cancel()
    }

    @Test fun enabledFollowsTheHeadsetWithoutDuplicates() = runTest(UnconfinedTestDispatcher()) {
        val enabled = MutableStateFlow(true); val has = MutableStateFlow(false); val suppressed = MutableStateFlow(false)
        val out = mutableListOf<Boolean>()
        val job = launch { BatteryNotificationPolicy.desired(enabled, has, suppressed).toList(out) }
        has.value = true; has.value = true; enabled.value = true
        has.value = false
        job.cancel()
        assertEquals(listOf(false, true, false), out)
    }

    @Test fun sleepTimerShutdownWinsOverTheSetting() = runTest(UnconfinedTestDispatcher()) {
        val enabled = MutableStateFlow(true); val has = MutableStateFlow(true); val suppressed = MutableStateFlow(false)
        val out = mutableListOf<Boolean>()
        val job = launch { BatteryNotificationPolicy.desired(enabled, has, suppressed).toList(out) }
        suppressed.value = true
        has.value = false; has.value = true   // reconnect after the timer ended: still nothing
        job.cancel()
        assertEquals(listOf(true, false), out)
        assertTrue(out.last() == false)
    }
}
