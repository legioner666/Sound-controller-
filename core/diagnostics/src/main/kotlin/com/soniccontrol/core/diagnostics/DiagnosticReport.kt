package com.soniccontrol.core.diagnostics

import com.soniccontrol.core.protocol.Json

/**
 * Builds the JSON diagnostics export. Contains no names of people, no accounts and no location;
 * device addresses are anonymised. The user decides whether to share the file.
 */
object DiagnosticReport {
    const val FORMAT_VERSION = 1

    fun build(
        appVersion: String,
        androidSdk: Int,
        phoneModel: String,
        createdAtMillis: Long,
        device: Map<String, Any?>?,
        events: List<DiagnosticEvent>,
        anonymizer: Anonymizer,
    ): String {
        val root = linkedMapOf<String, Any?>(
            "format" to "sonic-control-diagnostics",
            "formatVersion" to FORMAT_VERSION,
            "createdAtMillis" to createdAtMillis,
            "app" to mapOf("version" to appVersion),
            "phone" to mapOf("androidSdk" to androidSdk, "model" to phoneModel),
            "device" to device?.let { scrub(it, anonymizer) },
            "events" to events.map { e ->
                linkedMapOf(
                    "t" to e.timestampMillis,
                    "category" to e.category.name,
                    "message" to anonymizer.text(e.message),
                    "hex" to e.hex,
                    "device" to e.address?.let(anonymizer::address),
                )
            },
        )
        return Json.stringify(root)
    }

    private fun scrub(value: Any?, a: Anonymizer): Any? = when (value) {
        is String -> a.text(value)
        is Map<*, *> -> value.entries.associate { it.key.toString() to scrub(it.value, a) }
        is Iterable<*> -> value.map { scrub(it, a) }
        else -> value
    }
}
