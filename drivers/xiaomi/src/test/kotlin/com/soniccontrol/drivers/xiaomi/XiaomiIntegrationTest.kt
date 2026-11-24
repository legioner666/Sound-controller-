package com.soniccontrol.drivers.xiaomi

import com.soniccontrol.core.devices.ChannelState
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.DeviceIdentity
import com.soniccontrol.core.devices.DriverEnvironment
import com.soniccontrol.core.devices.FakeLink
import com.soniccontrol.core.devices.FakeTransport
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.StandardUuids
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
class XiaomiIntegrationTest {
    @Test fun handshakeReadSwitch() = runTest(UnconfinedTestDispatcher()) {
        var mode = 1
        val t = FakeTransport { raw ->
            val f = XiaomiGaia.parse(raw) ?: return@FakeTransport emptyList()
            when (f.command) {
                XiaomiGaia.CMD_HANDSHAKE -> listOf(Hex.decode("ff 03 00 04 00 0a 83 00 00 03 03 01"))
                XiaomiGaia.CMD_MODE_GET -> listOf(XiaomiGaia.frame(XiaomiGaia.VENDOR_DEVICE, XiaomiGaia.CMD_MODE_GET_REPLY, byteArrayOf(mode.toByte(), 1, 1, 0, 0)))
                XiaomiGaia.CMD_MODE_SET -> { mode = f.payload[0].toInt(); listOf(XiaomiGaia.frame(XiaomiGaia.VENDOR_DEVICE, XiaomiGaia.CMD_MODE_SET_ACK)) }
                else -> emptyList()
            }
        }
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(false), MutableStateFlow(false), DiagnosticSink { }, { 0L })
        val s = XiaomiDriver().createSession(FakeLink(name = "Xiaomi Buds 5 Pro", rfcomm = mapOf(StandardUuids.SPP to t)), DeviceIdentity("A", "Xiaomi Buds 5 Pro"), env)
        s.start()
        assertEquals(ChannelState.CONFIRMED, s.state.value.control)
        assertEquals(NoiseMode.ANC, s.state.value.noise?.mode)
        assertTrue(s.setNoiseMode(NoiseMode.OFF) is CommandOutcome.Confirmed)
        assertEquals(0, mode)
    }

    @Test fun unknownXiaomiModelOpensNothing() = runTest(UnconfinedTestDispatcher()) {
        val link = FakeLink(name = "Redmi Buds 5")
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(true), MutableStateFlow(false), DiagnosticSink { }, { 0L })
        XiaomiDriver().createSession(link, DeviceIdentity("A", "Redmi Buds 5"), env).start()
        assertTrue(link.opened.isEmpty())
    }

    @Test fun fuzz() {
        val rnd = Random(5)
        repeat(20_000) {
            val b = ByteArray(rnd.nextInt(0, 16)) { rnd.nextInt(256).toByte() }
            XiaomiGaia.parse(b); XiaomiGaia.split(b)
            if (XiaomiGaia.isAllowed(b)) assertTrue(b.size >= 8 && (b.size < 9 || b[8].toInt() != 4))
        }
    }
}
