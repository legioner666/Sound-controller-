package com.soniccontrol.core.protocol

object Crc {
    /** CRC-16/MODBUS: init 0xFFFF, reflected poly 0xA001. */
    fun crc16Modbus(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    /** Sum of bytes, low 8 bits. */
    fun sum8(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var s = 0
        for (i in from until to) s += bytes[i].toInt() and 0xFF
        return s and 0xFF
    }
}
