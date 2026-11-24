package com.soniccontrol.core.diagnostics

import java.security.MessageDigest

/**
 * Replaces Bluetooth addresses with a salted hash so exported reports cannot be linked to a device.
 * The salt is per installation and never leaves the phone.
 */
class Anonymizer(private val salt: String) {
    private val macRegex = Regex("(?i)\\b[0-9a-f]{2}(:[0-9a-f]{2}){5}\\b")

    fun address(address: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((salt + address.uppercase()).toByteArray())
        return "dev-" + digest.take(4).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    /** Scrubs any MAC address that appears inside free text. */
    fun text(text: String): String = macRegex.replace(text) { address(it.value) }
}
