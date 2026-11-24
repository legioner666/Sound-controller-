package com.soniccontrol.core.diagnostics

import com.soniccontrol.core.protocol.Json

/**
 * Builds the JSON diagnostics export. Contains no account, no location and no raw device identifiers:
 * every string, hex dump and value goes through [Redactor]. The user decides whether to share the file.
 */
object DiagnosticReport {
    const val FORMAT_VERSION = 3

    fun build(
        appVersion: String,
        androidSdk: Int,
        phoneModel: String,
        createdAtMillis: Long,
        device: Map<String, Any?>?,
        events: List<DiagnosticEvent>,
        redactor: Redactor,
    ): String {
        val root = linkedMapOf<String, Any?>(
            "format" to "sonic-control-diagnostics",
            "formatVersion" to FORMAT_VERSION,
            "createdAtMillis" to createdAtMillis,
            "privacy" to "addresses hashed with a per-install salt; device names, serial numbers and identifying GATT values removed",
            "app" to mapOf("version" to appVersion),
            "phone" to mapOf("androidSdk" to androidSdk, "model" to phoneModel),
            "device" to device?.let { scrub(it, redactor) },
            "events" to events.map { e ->
                linkedMapOf(
                    "t" to e.timestampMillis,
                    "category" to e.category.name,
                    "message" to redactor.text(e.message),
                    "hex" to e.hex?.let(redactor::hex),
                    "device" to e.address?.let(redactor::address),
                )
            },
        )
        return Json.stringify(root)
    }

    /** Keys ending in "Hex" are treated as hex dumps; everything else as text. */
    private fun scrub(value: Any?, r: Redactor, key: String = ""): Any? = when (value) {
        is String -> if (key.endsWith("Hex")) r.hex(value) else r.text(value)
        is Map<*, *> -> value.entries.associate { it.key.toString() to scrub(it.value, r, it.key.toString()) }
        is Iterable<*> -> value.map { scrub(it, r, key) }
        else -> value
    }
}
