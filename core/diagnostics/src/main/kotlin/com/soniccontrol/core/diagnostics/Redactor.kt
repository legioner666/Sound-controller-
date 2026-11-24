package com.soniccontrol.core.diagnostics

/**
 * Removes personal identifiers from diagnostics before export:
 * - Bluetooth addresses in text are replaced by salted hashes ([Anonymizer]);
 * - known device names (which users often personalise, e.g. "Anna's buds") are replaced in text;
 * - inside hex dumps, any known address (both byte orders) and any known name (UTF-8) is masked with "xx";
 * - values of characteristics that carry identifiers (name, serial, system id) are dropped entirely.
 *
 * Frames are kept otherwise: protocol research needs the bytes. The user decides whether to share the file.
 */
class Redactor(
    salt: String,
    knownAddresses: Collection<String>,
    knownNames: Collection<String>,
) {
    private val anonymizer = Anonymizer(salt)

    private val addressPatterns: List<ByteArray> = knownAddresses.mapNotNull(::macBytes).flatMap { listOf(it, it.reversedArray()) }

    private val names: List<String> = knownNames.map { it.trim() }.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }

    private val namePatterns: List<ByteArray> = names.map { it.toByteArray(Charsets.UTF_8) }

    fun address(address: String): String = anonymizer.address(address)

    fun text(text: String): String {
        var out = anonymizer.text(text)
        for (n in names) out = out.replace(n, NAME_PLACEHOLDER, ignoreCase = true)
        return out
    }

    /** Masks identifiers inside a hex dump ("aa bb cc"). Unparseable input is dropped. */
    fun hex(hex: String): String {
        val bytes = runCatching { hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16) } }.getOrNull()
            ?: return "<unparseable>"
        val mask = BooleanArray(bytes.size)
        for (pattern in addressPatterns + namePatterns) markMatches(bytes, pattern, mask)
        return bytes.indices.joinToString(" ") { if (mask[it]) "xx" else bytes[it].toString(16).padStart(2, '0') }
    }

    /** Value of a GATT characteristic, or null when the characteristic is known to identify the device or person. */
    fun characteristicValue(uuid: String, hex: String?): String? {
        if (hex == null) return null
        if (IDENTIFYING_CHARACTERISTICS.any { uuid.startsWith(it, ignoreCase = true) }) return "<redacted>"
        return hex(hex)
    }

    private fun markMatches(bytes: List<Int>, pattern: ByteArray, mask: BooleanArray) {
        if (pattern.isEmpty() || pattern.size > bytes.size) return
        for (start in 0..bytes.size - pattern.size) {
            var ok = true
            for (j in pattern.indices) {
                val a = bytes[start + j]
                val b = pattern[j].toInt() and 0xFF
                if (a != b && !(isAsciiLetter(b) && a == (b xor 0x20))) { ok = false; break }
            }
            if (ok) for (j in pattern.indices) mask[start + j] = true
        }
    }

    private fun isAsciiLetter(b: Int) = b in 0x41..0x5A || b in 0x61..0x7A

    companion object {
        const val NAME_PLACEHOLDER = "<device-name>"

        /** Device Name, Serial Number, System ID, Hardware Revision (often unit-specific). */
        val IDENTIFYING_CHARACTERISTICS = listOf("00002a00-", "00002a25-", "00002a23-", "00002a27-")

        fun macBytes(mac: String): ByteArray? {
            val parts = mac.split(":")
            if (parts.size != 6) return null
            return runCatching { ByteArray(6) { parts[it].toInt(16).toByte() } }.getOrNull()
        }
    }
}
