package com.soniccontrol.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** TASK 014: a synthetic btsnoop file built byte by byte (H4 datalink). */
class HciSnoopTest {
    private fun hex(s: String) = s.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private class Builder(datalink: Int = 1002) {
        val bytes = ByteArrayOutputStream()
        private val out = DataOutputStream(bytes)
        init { out.write("btsnoop\u0000".toByteArray()); out.writeInt(1); out.writeInt(datalink) }
        fun record(packet: ByteArray, received: Boolean, tsMicros: Long, orig: Int = packet.size) {
            out.writeInt(orig); out.writeInt(packet.size); out.writeInt(if (received) 1 else 0); out.writeInt(0); out.writeLong(tsMicros); out.write(packet)
        }
        /** H4 ACL packet: type 02, handle 0x0040 with PB flags, then [payload]. */
        fun acl(payload: ByteArray, pb: Int = 0x02): ByteArray {
            val h = 0x0040 or (pb shl 12)
            return byteArrayOf(0x02, (h and 0xFF).toByte(), (h shr 8).toByte(), (payload.size and 0xFF).toByte(), (payload.size shr 8).toByte()) + payload
        }
        fun att(opcode: Int, handle: Int, value: ByteArray): ByteArray {
            val l2 = 3 + value.size
            return byteArrayOf((l2 and 0xFF).toByte(), (l2 shr 8).toByte(), 0x04, 0x00, opcode.toByte(), (handle and 0xFF).toByte(), (handle shr 8).toByte()) + value
        }
    }

    @Test fun extractsWritesAndNotificationsOnly() {
        val b = Builder()
        b.record(b.acl(b.att(0x52, 0x2D, hex("aa 77 02 01 ff"))), received = false, tsMicros = 1_000_000)
        b.record(b.acl(b.att(0x1B, 0x2A, hex("aa 77 11 02 06 09 07 04 08 00 0c a1 09 01 0a 02 0b 00 0e a1"))), received = true, tsMicros = 1_250_000)
        b.record(byteArrayOf(0x04, 0x0E, 0x04, 0x01, 0x01, 0x10, 0x00), received = true, tsMicros = 1_300_000)       // HCI event: skipped
        val other = byteArrayOf(0x03, 0x00, 0x40, 0x00, 0x01, 0x02, 0x03)                                            // L2CAP CID 0x0040
        b.record(b.acl(other), received = true, tsMicros = 1_400_000)
        b.record(b.acl(b.att(0x0A, 0x2A, ByteArray(0))), received = false, tsMicros = 1_500_000)                    // ATT read: skipped
        val r = HciSnoop.parse(b.bytes.toByteArray().inputStream())
        assertEquals(5, r.records)
        assertEquals(2, r.values.size)
        assertEquals(HciSnoop.Direction.PHONE_TO_HEADSET, r.values[0].direction)
        assertEquals(0x2D, r.values[0].handle)
        assertEquals(HciSnoop.Direction.HEADSET_TO_PHONE, r.values[1].direction)
        assertEquals(250L, r.values[1].millis)
        assertTrue(r.values[1].value.contentEquals(hex("aa 77 11 02 06 09 07 04 08 00 0c a1 09 01 0a 02 0b 00 0e a1")))
    }

    @Test fun reassemblesFragmentedNotifications() {
        val b = Builder()
        val eq = hex("aa a2 74 00 04 03") + ByteArray(114)
        val sdu = b.att(0x1B, 0x2A, eq)
        b.record(b.acl(sdu.copyOfRange(0, 27), pb = 0x02), received = true, tsMicros = 10)
        b.record(b.acl(sdu.copyOfRange(27, 80), pb = 0x01), received = true, tsMicros = 11)
        b.record(b.acl(sdu.copyOfRange(80, sdu.size), pb = 0x01), received = true, tsMicros = 12)
        val r = HciSnoop.parse(b.bytes.toByteArray().inputStream())
        assertEquals(1, r.values.size)
        assertEquals(120, r.values.single().value.size)
    }

    @Test fun truncatedAndForeignFilesAreHandled() {
        val b = Builder()
        b.record(b.acl(b.att(0x52, 0x2D, hex("aa 91 01 11"))), received = false, tsMicros = 5)
        val full = b.bytes.toByteArray()
        val cut = full.copyOfRange(0, full.size - 2)
        val r = HciSnoop.parse(cut.inputStream())
        assertTrue(r.truncated); assertTrue(r.values.isEmpty())
        assertNotNull(HciSnoop.parse("hello world, not a log".toByteArray().inputStream()).error)
        assertNotNull(HciSnoop.parse(ByteArray(3).inputStream()).error)
        // A record cut by the logger itself (included < original) is never trusted.
        val c = Builder(); c.record(c.acl(c.att(0x1B, 0x2A, hex("aa 91 09 12 01 01 02 00 03 00 04 07"))), received = true, tsMicros = 1, orig = 999)
        assertTrue(HciSnoop.parse(c.bytes.toByteArray().inputStream()).values.isEmpty())
    }
}

/** TASK 014 follow-up: the JBL candidate touch event keeps its 3 bytes in the export; longer AA 23 frames do not. */
class TouchCandidateExportTest {
    private val r = Redactor("salt", emptyList(), emptyList())
    @Test fun aa23ShortIsExported() {
        assertEquals("aa 23 03 01 02 03", HexPolicy.export("harman", "aa 23 03 01 02 03", r))
        assertEquals("<aa cmd=0x23 length=6>", HexPolicy.export("harman", "aa 23 06 01 02 03 04 05 06", r))
    }
}
