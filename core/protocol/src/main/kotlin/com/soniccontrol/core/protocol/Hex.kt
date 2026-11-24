package com.soniccontrol.core.protocol

/** Hex helpers used by the protocol layer, logs and tests. */
object Hex {
    fun encode(bytes: ByteArray, separator: String = " "): String =
        bytes.joinToString(separator) { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** Accepts "aa 21 01", "AA2101", "aa:21:01". Throws [IllegalArgumentException] on bad input. */
    fun decode(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() && it != ':' && it != '-' }
        require(clean.length % 2 == 0) { "Odd number of hex digits" }
        return ByteArray(clean.length / 2) { i ->
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex digit" }
            ((hi shl 4) or lo).toByte()
        }
    }
}

fun ByteArray.toHex(): String = Hex.encode(this)

fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

fun ByteArray.u16le(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

fun ByteArray.u16be(index: Int): Int = (u8(index) shl 8) or u8(index + 1)
