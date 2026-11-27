package com.soniccontrol.core.database

import com.soniccontrol.core.devices.BatteryStatus

/** A local equaliser profile (stored on the phone). Gains are limited to ±12 dB. */
data class EqProfile(val id: String, val name: String, val gainsDb: List<Float>, val builtIn: Boolean = false) {
    init {
        require(gainsDb.size == BANDS.size) { "expected ${BANDS.size} bands" }
    }

    fun clamped() = copy(gainsDb = gainsDb.map { it.coerceIn(MIN_DB, MAX_DB) })

    companion object {
        val BANDS = listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        const val MIN_DB = -12f
        const val MAX_DB = 12f

        val PRESETS = listOf(
            EqProfile("flat", "Flat", List(10) { 0f }, builtIn = true),
            EqProfile("bass", "Bass", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f), builtIn = true),
            EqProfile("voice", "Voice", listOf(-3f, -2f, -1f, 0f, 2f, 3f, 3f, 2f, 0f, -1f), builtIn = true),
            EqProfile("treble", "Treble", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f, 4f), builtIn = true),
        )
    }
}

/** One stored battery observation. [lowest] is the lowest listening part; the case is kept separately. */
data class BatteryRecord(val deviceKey: String, val timeMillis: Long, val left: Int?, val right: Int?, val case: Int?, val single: Int?, val source: String) {
    val lowest: Int? get() = listOfNotNull(left, right, single).minOrNull()

    companion object {
        fun from(deviceKey: String, s: BatteryStatus): BatteryRecord? {
            if (!s.hasAnyValue) return null
            return BatteryRecord(deviceKey, s.timestampMillis, s.left?.percent, s.right?.percent, s.case?.percent, s.single?.percent, s.source.name)
        }
    }
}

object BatteryHistory {
    const val MAX_RECORDS = 2000
    private const val MIN_INTERVAL_MILLIS = 5 * 60_000L

    /** Store a reading when values changed or enough time passed; avoids flooding with duplicates. */
    fun shouldAppend(last: BatteryRecord?, next: BatteryRecord): Boolean {
        if (last == null) return true
        val changed = last.left != next.left || last.right != next.right || last.case != next.case || last.single != next.single
        return changed || next.timeMillis - last.timeMillis >= MIN_INTERVAL_MILLIS
    }

    /**
     * Discharge rate in percent per hour from the most recent continuous falling run of [records]
     * for one device. Null when there is not enough real data (at least 30 minutes and 5 points of decline).
     */
    fun dischargeRatePerHour(records: List<BatteryRecord>): Double? {
        val pts = records.mapNotNull { r -> r.lowest?.let { r.timeMillis to it } }.sortedBy { it.first }
        if (pts.size < 2) return null
        var start = pts.size - 1
        while (start > 0 && pts[start - 1].second >= pts[start].second && pts[start].first - pts[start - 1].first < 60 * 60_000L) start--
        val run = pts.subList(start, pts.size)
        val drop = run.first().second - run.last().second
        val hours = (run.last().first - run.first().first) / 3_600_000.0
        if (run.size < 2 || hours < 0.5 || drop < 5) return null
        return drop / hours
    }

    /** Remaining hours at the measured rate, or null when no estimate is possible. */
    fun remainingHours(currentPercent: Int?, ratePerHour: Double?): Double? {
        if (currentPercent == null || ratePerHour == null || ratePerHour <= 0) return null
        return currentPercent / ratePerHour
    }
}

internal object Codecs {
    /** `address|leftLayout|rightLayout|leftRaw|rightRaw|time` per line; layouts may be empty (unknown combination). */
    fun encodeBackups(m: Map<String, com.soniccontrol.core.devices.GestureBackup>): String = m.entries.joinToString("\n") { (k, b) ->
        listOf(k, b.left?.name.orEmpty(), b.right?.name.orEmpty(), b.leftRaw.orEmpty(), b.rightRaw.orEmpty(), b.savedAt.toString()).joinToString("|")
    }

    fun decodeBackups(text: String?): Map<String, com.soniccontrol.core.devices.GestureBackup> = text.orEmpty().lines().mapNotNull { line ->
        val p = line.split("|")
        if (p.size != 6 || p[0].isBlank()) return@mapNotNull null
        fun layout(s: String) = s.takeIf { it.isNotEmpty() }?.let { runCatching { com.soniccontrol.core.devices.GestureLayout.valueOf(it) }.getOrNull() }
        p[0] to com.soniccontrol.core.devices.GestureBackup(layout(p[1]), layout(p[2]), p[3].ifEmpty { null }, p[4].ifEmpty { null }, p[5].toLongOrNull() ?: return@mapNotNull null)
    }.toMap()

    fun encodeProfiles(list: List<EqProfile>): String = list.joinToString("\n") { p ->
        listOf(p.id, p.name.replace("|", " ").replace("\n", " "), p.gainsDb.joinToString(","), if (p.builtIn) "1" else "0").joinToString("|")
    }

    fun decodeProfiles(text: String?): List<EqProfile> = text.orEmpty().lines().mapNotNull { line ->
        val parts = line.split("|")
        if (parts.size != 4) return@mapNotNull null
        val gains = parts[2].split(",").mapNotNull { it.toFloatOrNull() }
        if (gains.size != EqProfile.BANDS.size) return@mapNotNull null
        EqProfile(parts[0], parts[1], gains, parts[3] == "1").clamped()
    }

    fun encodeHistory(list: List<BatteryRecord>): String = list.joinToString("\n") { r ->
        listOf(r.deviceKey, r.timeMillis, r.left ?: "", r.right ?: "", r.case ?: "", r.single ?: "", r.source).joinToString(";")
    }

    fun decodeHistory(text: String?): List<BatteryRecord> = text.orEmpty().lines().mapNotNull { line ->
        val p = line.split(";")
        if (p.size != 7) return@mapNotNull null
        val t = p[1].toLongOrNull() ?: return@mapNotNull null
        BatteryRecord(p[0], t, p[2].toIntOrNull(), p[3].toIntOrNull(), p[4].toIntOrNull(), p[5].toIntOrNull(), p[6])
    }
}
