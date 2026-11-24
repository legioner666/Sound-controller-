package com.soniccontrol.drivers.sony

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Emulates a WH-1000XM5: acks every command, answers handshake and NC GET, notifies after SET. */
private class FakeXm5 {
    var effect = 1; var mode = 0; var level = 10
    private var seq = 0
    val acksReceived = mutableListOf<Int>()
    val transport = FakeTransport { raw -> reply(raw) }

    private fun cmd(payload: ByteArray): ByteArray = SonyMdr.encode(SonyMdr.TYPE_COMMAND, seq, payload).also { seq = 1 - seq }

    private fun reply(raw: ByteArray): List<ByteArray> {
        val f = SonyMdr.decode(raw) ?: return emptyList()
        if (f.type == SonyMdr.TYPE_ACK) { acksReceived += f.seq; return emptyList() }
        val ack = SonyMdr.ack(f.seq)
        val p = f.payload
        return when {
            p.contentEquals(SonyMdr.HANDSHAKE) -> listOf(ack, cmd(Hex.decode("01 00 40 10 00 00 00 00")))
            p.contentEquals(SonyMdr.ncGetV2()) -> listOf(ack, cmd(byteArrayOf(0x67, 0x17, 1, effect.toByte(), mode.toByte(), 0, level.toByte())))
            p.size == 7 && p[0] == 0x68.toByte() -> {
                effect = p[3].toInt(); mode = p[4].toInt(); level = p[6].toInt()
                listOf(ack, cmd(byteArrayOf(0x69, 0x17, 1, effect.toByte(), mode.toByte(), 0, level.toByte())))
            }
            else -> listOf(ack)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SonyIntegrationTest {
    @Test fun readsAndSwitchesAndAcknowledges() = runTest(UnconfinedTestDispatcher()) {
        val dev = FakeXm5()
        val link = FakeLink(name = "WH-1000XM5", rfcomm = mapOf(SonyMdr.V2_UUID to dev.transport))
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(false), MutableStateFlow(false), DiagnosticSink { }, { 0L })
        val s = SonyDriver().createSession(link, DeviceIdentity("A", "WH-1000XM5"), env)
        s.start()
        assertEquals(ChannelState.CONFIRMED, s.state.value.control)
        assertEquals(NoiseMode.ANC, s.state.value.noise?.mode)
        assertTrue(s.setNoiseMode(NoiseMode.AMBIENT) is CommandOutcome.Confirmed)
        assertEquals(NoiseMode.AMBIENT, s.state.value.noise?.mode)
        assertEquals(10, dev.level) // the stored ambient level is preserved
        assertTrue(dev.acksReceived.isNotEmpty())
        assertTrue(s.setNoiseMode(NoiseMode.TALK_THRU) is CommandOutcome.Unsupported)
    }

    @Test fun guardRejectsGarbage() {
        val rnd = Random(3)
        repeat(20_000) {
            val p = ByteArray(rnd.nextInt(0, 12)) { rnd.nextInt(256).toByte() }
            if (SonyMdr.isAllowedPayload(p)) {
                assertTrue(p.contentEquals(SonyMdr.HANDSHAKE) || p[0] == 0x66.toByte() || p[0] == 0x68.toByte())
            }
            SonyMdr.decode(p); SonyMdr.parseNc(p); SonyMdr.split(p)
        }
        assertFalse(SonyMdr.isAllowedPayload(SonyMdr.ncSetV2(NoiseMode.ANC, 5).copyOf(6)))
    }
}
