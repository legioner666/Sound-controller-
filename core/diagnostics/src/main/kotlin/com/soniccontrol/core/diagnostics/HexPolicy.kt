package com.soniccontrol.core.diagnostics

/**
 * Structured allowlist for the hex bytes in the standard export (TASK 009).
 *
 * Only frame layouts that are known to carry no identifiers are exported as bytes. Everything else is reduced to its
 * shape ("group/code/length", "cmd/length", "N bytes"), so an unknown or future message can never smuggle a MAC address,
 * serial number, account key, Find Hub identifier or another person's data into a file the user shares.
 * The [Redactor] still masks any known address or name inside the allowed frames.
 */
object HexPolicy {
    const val MAX_EXPORTED_BYTES = 32

    /** Fast Pair Message Stream: group 3 codes whose payload is non-identifying (model id, battery, remaining time, active, firmware, request). */
    private val FAST_PAIR_ALLOWED_CODES = setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x09)

    /** Vendor "AA" frames: 0x91 listening mode, 0x9B notify control, 0x25 battery (short only), 0xEE error reply. */
    private val AA_ALLOWED_CMDS = setOf(0x91, 0x9B, 0xEE)

    /** GATT reads whose values are safe: battery level, firmware/software revision, Fast Pair model id and PSM state. */
    private val GATT_ALLOWED = listOf("00002a19-", "00002a26-", "00002a28-", "fe2c1233-", "fe2c1239-")

    fun export(message: String, hex: String, redactor: Redactor): String {
        val bytes = parse(hex) ?: return "<unparseable>"
        val shaped = shape(message, bytes)
        return shaped ?: redactor.hex(bytes.joinToString(" ") { it.toString(16).padStart(2, '0') })
    }

    private fun parse(hex: String): List<Int>? =
        runCatching { hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).also { b -> require(b in 0..255) } } }.getOrNull()

    /** Null = the frame is on the allowlist and is exported as bytes; otherwise a shape-only string. */
    private fun shape(message: String, b: List<Int>): String? {
        if (b.size > MAX_EXPORTED_BYTES) return "<${b.size} bytes>"
        val m = message.lowercase()
        if (m.startsWith("read ")) {
            return if (GATT_ALLOWED.any { m.contains(it) }) null else "<${b.size} bytes>"
        }
        // Fast Pair frame: group | code | length u16 BE | payload
        if (b.size >= 4 && b[0] == 0x03 && (b[2] shl 8 or b[3]) == b.size - 4) {
            val code = b[1]
            if (code == 0x02) return "03 02 00 ${"%02x".format(b[3])} " + List(b[3]) { "xx" }.joinToString(" ")      // BLE address: payload never exported
            return if (code in FAST_PAIR_ALLOWED_CODES && validFastPairPayload(code, b.subList(4, b.size))) null
            else "<fast-pair group=0x3 code=0x${code.toString(16)} length=${b.size - 4}>"
        }
        if (b.size >= 2 && b[0] == 0x07) return "<fast-pair group=0x7 code=0x${b[1].toString(16)} length=${(b.size - 4).coerceAtLeast(0)}>"
        // Vendor AA frame: AA | cmd | len | payload   (and the 0x12 report variant "AA 91 len 12 ...")
        if (b.size >= 3 && b[0] == 0xAA) {
            val cmd = b[1]
            return when {
                b[2] != b.size - 3 -> "<aa cmd=0x${cmd.toString(16)} length=${b.size - 3}>"
                cmd in AA_ALLOWED_CMDS && validAaPayload(cmd, b.subList(3, b.size)) -> null
                cmd == 0x25 && b.size <= 8 -> null
                // TASK 014: JBL candidate touch event, exactly 3 payload bytes (too short to carry an identifier).
                cmd == 0x23 && b.size == 6 -> null
                else -> "<aa cmd=0x${cmd.toString(16)} length=${b.size - 3}>"
            }
        }
        return "<${b.size} bytes, header ${b.take(2).joinToString(" ") { it.toString(16).padStart(2, '0') }}>"
    }

    /** Payload structure and value ranges per allowed Fast Pair code; anything else is exported by shape only. */
    private fun validFastPairPayload(code: Int, p: List<Int>): Boolean = when (code) {
        0x01 -> p.size == 3                                                       // model id
        0x03 -> p.size == 3 && p.all { (it and 0x7F) == 0x7F || (it and 0x7F) in 0..100 }    // 0x7F/0xFF = unknown level (0xFF: charging, unknown level)    // left, right, case: level or 0x7F unknown, high bit = charging
        0x04 -> p.size == 2                                                       // remaining minutes
        0x05 -> p.isEmpty()                                                      // active components request carries no payload (03 05 00 00)
        0x06 -> p.size == 1 && p[0] <= 0x03                                       // bit0 right, bit1 left
        0x09 -> p.size in 1..16 && p.all { it in 0x20..0x7E }                     // firmware version text
        else -> false
    }

    private fun validAaPayload(cmd: Int, p: List<Int>): Boolean = when (cmd) {
        0x91 -> p.isNotEmpty() && p[0] in setOf(0x10, 0x11, 0x12) && (p[0] == 0x11 && p.size == 1 || p.size in 3..13 && (p.size - 1) % 2 == 0 && p.drop(1).chunked(2).all { it[0] in 1..4 && it[1] in 0..0x0F })
        0x9B -> p.size == 2 && p.all { it in 0..1 }
        0xEE -> p.size == 1
        else -> false
    }
}
