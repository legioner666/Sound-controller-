package com.soniccontrol.core.background

/** What the notification shows. Percentages are null when unknown (never shown as 0 %). */
data class MonitorSummary(
    val deviceName: String,
    val connected: Boolean,
    val left: Int? = null,
    val right: Int? = null,
    val case: Int? = null,
    val single: Int? = null,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
    val stale: Boolean = false,
    val reconnect: String? = null,
)

/** Pure text builder so the notification wording is testable. Labels come from resources (localised), values from here. */
object MonitorText {
    data class Labels(val left: String, val right: String, val case: String, val headset: String, val charging: String, val stale: String, val reconnecting: String, val unknown: String)

    fun body(s: MonitorSummary, l: Labels): String {
        val parts = ArrayList<String>()
        fun comp(label: String, pct: Int?, charging: Boolean) = "$label ${pct?.let { "$it%" } ?: l.unknown}${if (charging) " ⚡" else ""}"
        if (s.left != null || s.right != null || s.case != null) {
            parts += comp(l.left, s.left, s.leftCharging); parts += comp(l.right, s.right, s.rightCharging)
            if (s.case != null) parts += comp(l.case, s.case, s.caseCharging)
        } else if (s.single != null) parts += comp(l.headset, s.single, false)
        val line = parts.joinToString(" · ")
        val tail = buildList { if (s.stale) add(l.stale); s.reconnect?.let { add("${l.reconnecting}: $it") } }.joinToString(" · ")
        return listOf(line, tail).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** Changes worth a notification refresh; identical content is never re-posted (battery and CPU). */
    fun sameContent(a: MonitorSummary?, b: MonitorSummary?) = a == b
}
