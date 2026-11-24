package com.soniccontrol.drivers.nothing

import com.soniccontrol.core.devices.ChannelState
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.DeviceIdentity
import com.soniccontrol.core.devices.DriverEnvironment
import com.soniccontrol.core.devices.FakeLink
import com.soniccontrol.core.devices.FakeTransport
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.protocol.Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class NothingIntegrationTest {
    private class FakeEar(var applies: Boolean = true) {
        var mode = 0x05
        val transport = FakeTransport { raw ->
            val f = NtLink.parse(raw) ?: return@FakeTransport emptyList()
            when (f.command) {
                NtLink.CMD_DEVICE_INFO -> listOf(NtLink.frame(NtLink.CMD_DEVICE_INFO, NtLink.DIR_ANSWER, Hex.decode("01")))
                NtLink.CMD_BATTERY -> listOf(NtLink.frame(NtLink.CMD_BATTERY, NtLink.DIR_ANSWER, Hex.decode("02 02 40 03 3c")))
                NtLink.CMD_NOISE -> listOf(NtLink.frame(NtLink.CMD_NOISE, NtLink.DIR_ANSWER, byteArrayOf(1, mode.toByte(), 0)))
                NtLink.CMD_NOISE_SET -> { if (applies) mode = f.payload[1].toInt(); listOf(NtLink.frame(NtLink.CMD_NOISE_SET, NtLink.DIR_ACK)) }
                else -> emptyList()
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.session(dev: FakeEar) = NothingDriver().createSession(
        FakeLink(name = "Ear (2)", rfcomm = mapOf(NtLink.UUID_NT to dev.transport)), DeviceIdentity("A", "Ear (2)"),
        DriverEnvironment(backgroundScope, MutableStateFlow(false), MutableStateFlow(false), DiagnosticSink { }, { testScheduler.currentTime }),
    )

    @Test fun readsBatteryAndConfirmsModeByReadBack() = runTest(UnconfinedTestDispatcher()) {
        val dev = FakeEar()
        val s = session(dev)
        s.start(); testScheduler.advanceUntilIdle()
        assertEquals(ChannelState.CONFIRMED, s.state.value.control)
        assertEquals(64, s.state.value.battery?.left?.percent)
        assertTrue(s.setNoiseMode(NoiseMode.AMBIENT) is CommandOutcome.Confirmed)
        assertEquals(0x07, dev.mode)
    }

    @Test fun ackWithoutChangeIsFailure() = runTest(UnconfinedTestDispatcher()) {
        val s = session(FakeEar(applies = false))
        s.start(); testScheduler.advanceUntilIdle()
        assertTrue(s.setNoiseMode(NoiseMode.ANC) is CommandOutcome.Failed)
    }

    @Test fun fuzz() {
        val rnd = Random(11)
        repeat(20_000) {
            val b = ByteArray(rnd.nextInt(0, 24)) { rnd.nextInt(256).toByte() }
            NtLink.parse(b); NtLink.split(b)
            if (NtLink.isAllowed(b)) assertTrue(NtLink.parse(b) != null)
        }
    }
}
