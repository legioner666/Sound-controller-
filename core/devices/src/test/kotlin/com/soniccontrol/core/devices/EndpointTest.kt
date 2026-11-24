package com.soniccontrol.core.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointTest {
    private fun obs(addr: String, name: String?, rssi: Int = -50, fp: Boolean = false, conn: Boolean = true) =
        BleObservation(addr, name, rssi, hasFastPairData = fp, connectable = conn)

    @Test fun leTwinByNameIsAutomatic() {
        val r = EndpointResolver.rank("AA:AA", "JBL Tune Beam 2", setOf("jbl"), listOf(obs("BB:BB", "JBL Tune Beam 2 LE"), obs("CC:CC", "Kitchen TV", -40)))
        assertEquals("BB:BB", r.first().observation.address)
        assertTrue(r.first().auto)
        assertTrue(r.none { it.observation.address == "CC:CC" })
    }

    @Test fun fastPairOnlyIsNotAutomatic() {
        val r = EndpointResolver.rank("AA:AA", "JBL Tune Beam 2", setOf("jbl"), listOf(obs("DD:DD", null, -50, fp = true)))
        assertTrue(r.all { !it.auto })
    }

    @Test fun nonConnectableIsPenalised() {
        val r = EndpointResolver.rank("AA:AA", "X", emptySet(), listOf(obs("BB:BB", "X", conn = false)))
        assertTrue(r.all { !it.auto })
    }

    @Test fun differentModelNumberDoesNotMatchAutomatically() {
        val r = EndpointResolver.rank("AA:AA", "JBL Tune Beam 2", setOf("jbl"), listOf(obs("EE:EE", "JBL Tune Beam 3", -70)))
        assertTrue(r.all { !it.auto })
    }

    private fun advert(vararg tail: Int, filter: Int = 2, type: Int = 3, count: Int = tail.size): ByteArray {
        val b = ArrayList<Int>()
        b += 0x00; b += (filter shl 4); repeat(filter) { b += 0x11 }
        b += 0x11; b += 0x42
        b += (count shl 4) or type
        tail.forEach { b += it }
        return b.map { it.toByte() }.toByteArray()
    }

    @Test fun parsesThreeValues() {
        val s = FastPairAdvert.parseBattery(advert(0x50, 0x80 or 0x3C, 0x7F), 1)!!
        assertEquals(80, s.left?.percent); assertEquals(60, s.right?.percent); assertEquals(true, s.right?.charging)
        assertNull(s.case?.percent)
        assertEquals(DataSource.FAST_PAIR_ADVERT, s.source)
    }

    @Test fun rejectsMalformed() {
        assertNull(FastPairAdvert.parseBattery(advert(0x50, 0x50, 0x50, count = 2), 1))
        assertNull(FastPairAdvert.parseBattery(advert(0x50, type = 7), 1))
        assertNull(FastPairAdvert.parseBattery(byteArrayOf(0, 1), 1))
        assertNull(FastPairAdvert.parseBattery(advert(0x50, 0x50, 0x50).copyOf(9), 1))
    }

    private fun gatt(outcome: String) = ConnectionAttempt("gatt", "LE", "classic-address", "a", "BONDED", 100, null, null, outcome)

    @Test fun adviceNoEndpointWhenEveryAttemptTimesOut() {
        val links = LinkStatus(AudioLinkState.CONNECTED, ChannelState.FAILED, ChannelState.FAILED, ChannelState.UNAVAILABLE)
        assertEquals(ConnectionAdvice.NO_LE_ENDPOINT, ConnectionAdvisor.advise(listOf(gatt("NO_CALLBACK_TIMEOUT"), gatt("NO_CALLBACK_TIMEOUT")), links))
        assertEquals(ConnectionAdvice.GATT_REFUSED, ConnectionAdvisor.advise(listOf(gatt("NO_CALLBACK_TIMEOUT"), gatt("STATUS_133")), links))
        assertEquals(ConnectionAdvice.NONE, ConnectionAdvisor.advise(emptyList(), links.copy(control = ChannelState.CONFIRMED)))
    }
}
