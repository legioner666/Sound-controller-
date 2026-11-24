package com.soniccontrol.drivers.jbl

import com.soniccontrol.core.devices.Brand
import com.soniccontrol.core.devices.CatalogEntry
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.SupportStatus

enum class JblStack { HARMAN_AA, PROTOCOL_4, AIROHA_RACE, UNIDENTIFIED }

data class JblModelInfo(val name: String, val stack: JblStack, val pids: Set<Int> = emptySet(), val earbuds: Boolean = true, val hasAnc: Boolean = true)

/**
 * JBL model table.
 *
 * Model names, product ids and stack assignments for the H-AA and Airoha lines are taken from
 * Earshot (https://github.com/HellzYeahh/earshot, MIT License, Copyright (c) 2026 Earshot contributors),
 * file protocol/.../jbl/JblModel.kt and airoha/RaceWriteGuard.kt. See LICENSES.md.
 */
object JblCatalog {
    const val DRIVER_ID = "jbl"

    /** Models whose control protocol was confirmed on physical hardware by public research. */
    private val confirmedOnHardware = setOf("Tune 230NC TWS", "Wave Buds 2", "Tour Pro 3")

    val models: List<JblModelInfo> = listOf(
        JblModelInfo("Live 460NC", JblStack.HARMAN_AA, setOf(0x2031, 0x2032), earbuds = false),
        JblModelInfo("Live 660NC", JblStack.HARMAN_AA, setOf(0x2033, 0x2034), earbuds = false),
        JblModelInfo("Live Pro+ TWS", JblStack.HARMAN_AA, setOf(0x2043, 0x203B, 0x2060)),
        JblModelInfo("Tour Pro+ TWS", JblStack.HARMAN_AA, setOf(0x2020, 0x202C)),
        JblModelInfo("Tour One", JblStack.HARMAN_AA, setOf(0x2021, 0x2028), earbuds = false),
        JblModelInfo("Reflect Flow Pro", JblStack.HARMAN_AA, setOf(0x202D, 0x202E)),
        JblModelInfo("Tune 130NC TWS", JblStack.HARMAN_AA, setOf(0x2049, 0x204A)),
        JblModelInfo("Tune 230NC TWS", JblStack.HARMAN_AA, setOf(0x204B, 0x204C)),
        JblModelInfo("Tune 235NC TWS", JblStack.HARMAN_AA, setOf(0x20E5)),
        JblModelInfo("Live Pro 2 TWS", JblStack.HARMAN_AA, setOf(0x2058, 0x2059)),
        JblModelInfo("Tune Flex", JblStack.HARMAN_AA, setOf(0x207F, 0x2080)),
        JblModelInfo("Tour One M2", JblStack.HARMAN_AA, setOf(0x2085, 0x2086), earbuds = false),
        JblModelInfo("Live Flex", JblStack.HARMAN_AA, setOf(0x2099, 0x209A)),
        JblModelInfo("Tour Pro 2", JblStack.HARMAN_AA, setOf(0x2093, 0x2094, 0x20E8)),
        JblModelInfo("Tune Beam", JblStack.HARMAN_AA, setOf(0x20BB, 0x20BC)),
        JblModelInfo("Tune Buds", JblStack.HARMAN_AA, setOf(0x20B9, 0x20BA)),
        JblModelInfo("Live 670NC", JblStack.HARMAN_AA, setOf(0x20CC), earbuds = false),
        JblModelInfo("Live 770NC", JblStack.HARMAN_AA, setOf(0x20CE, 0x20CF), earbuds = false),
        JblModelInfo("Live Beam 3", JblStack.HARMAN_AA, setOf(0x20EF)),
        JblModelInfo("Live Flex 3", JblStack.HARMAN_AA, setOf(0x20F0)),
        JblModelInfo("Wave Buds 2", JblStack.HARMAN_AA, setOf(0x2100)),
        JblModelInfo("Wave Flex 2", JblStack.HARMAN_AA, setOf(0x20FF)),
        JblModelInfo("Vibe Buds 2", JblStack.HARMAN_AA, setOf(0x2104)),
        JblModelInfo("Vibe Flex 2", JblStack.HARMAN_AA, setOf(0x2103)),
        JblModelInfo("Tune Buds 2", JblStack.HARMAN_AA, setOf(0x210C)),
        JblModelInfo("Tune Flex 2", JblStack.HARMAN_AA, setOf(0x210E)),
        JblModelInfo("Tune Beam 2", JblStack.HARMAN_AA, setOf(0x210D)),
        JblModelInfo("Tune 720BT", JblStack.HARMAN_AA, earbuds = false, hasAnc = false),
        JblModelInfo("Tour Pro 3", JblStack.AIROHA_RACE),
        JblModelInfo("Live Buds 3", JblStack.AIROHA_RACE),
        JblModelInfo("Live Free 2", JblStack.AIROHA_RACE),
        JblModelInfo("Reflect Aero", JblStack.AIROHA_RACE),
        JblModelInfo("Tune 670NC", JblStack.AIROHA_RACE, earbuds = false),
        JblModelInfo("Tune 770NC", JblStack.AIROHA_RACE, earbuds = false),
        JblModelInfo("Endurance Race 2", JblStack.AIROHA_RACE),
        JblModelInfo("Wave Beam 2", JblStack.AIROHA_RACE),
        JblModelInfo("Vibe Beam 2", JblStack.AIROHA_RACE),
        JblModelInfo("Tune 780NC", JblStack.PROTOCOL_4, earbuds = false),
        // First physical test device of this project. Its protocol is identified in the Lab on first connection.
        JblModelInfo("Tune Beam 3", JblStack.UNIDENTIFIED),
    )

    /** JBL Protocol 4 product ids (2025+), from Earshot's JblModel.NEWER_PROTOCOL_PIDS (MIT). */
    val PROTOCOL_4_PIDS: Set<Int> = setOf(
        0x2101, 0x2160, 0x2161, 0x215E, 0x215F, 0x21A4, 0x2153, 0x2133, 0x2134, 0x2150, 0x214F, 0x2151, 0x2152,
        0x2179, 0x217B, 0x217D, 0x21B7, 0x21B8, 0x21B9, 0x21B6, 0x21AB, 0x21AC, 0x21AD, 0x21B2, 0x21B3, 0x21B4,
        0x21AE, 0x2193,
    )

    fun statusFor(model: JblModelInfo): SupportStatus = when (model.stack) {
        JblStack.HARMAN_AA, JblStack.AIROHA_RACE ->
            if (model.name in confirmedOnHardware || model.stack == JblStack.HARMAN_AA) SupportStatus.SUPPORTED else SupportStatus.EXPERIMENTAL
        JblStack.PROTOCOL_4, JblStack.UNIDENTIFIED -> SupportStatus.EXPERIMENTAL
    }

    fun features(model: JblModelInfo): Map<Feature, SupportStatus> {
        val s = statusFor(model)
        val m = linkedMapOf(
            Feature.CONNECTION_STATUS to SupportStatus.BASIC,
            Feature.SYSTEM_VOLUME to SupportStatus.BASIC,
            Feature.MEDIA_CONTROL to SupportStatus.BASIC,
            Feature.BATTERY to SupportStatus.SUPPORTED,
        )
        when (model.stack) {
            JblStack.HARMAN_AA -> {
                if (model.earbuds) m[Feature.BATTERY_PER_COMPONENT] = s
                if (model.hasAnc) { m[Feature.NOISE_CONTROL] = s; m[Feature.TALK_THRU] = s }
            }
            JblStack.AIROHA_RACE -> if (model.hasAnc) { m[Feature.NOISE_CONTROL] = s; m[Feature.TALK_THRU] = s }
            JblStack.PROTOCOL_4 -> m[Feature.BATTERY_PER_COMPONENT] = SupportStatus.EXPERIMENTAL
            JblStack.UNIDENTIFIED -> {
                m[Feature.BATTERY_PER_COMPONENT] = SupportStatus.EXPERIMENTAL
                m[Feature.NOISE_CONTROL] = SupportStatus.EXPERIMENTAL
            }
        }
        return m
    }

    private fun protocolName(stack: JblStack) = when (stack) {
        JblStack.HARMAN_AA -> "JBL H-AA (Harman GATT)"
        JblStack.PROTOCOL_4 -> "JBL Protocol 4 (read-only)"
        JblStack.AIROHA_RACE -> "Airoha RACE (RFCOMM)"
        JblStack.UNIDENTIFIED -> "Identified on connection"
    }

    val entries: List<CatalogEntry> = models.map { m ->
        CatalogEntry(
            brand = Brand.JBL,
            model = m.name,
            driverId = DRIVER_ID,
            protocol = protocolName(m.stack),
            features = features(m),
            aliases = listOf("JBL ${m.name}", "JBL ${m.name.removeSuffix(" TWS")}").distinct(),
            notes = when {
                m.stack == JblStack.UNIDENTIFIED -> "Priority test device. Open the Lab and run the protocol probe."
                m.name in confirmedOnHardware -> "Protocol confirmed on physical hardware in public research."
                else -> ""
            },
        )
    }

    fun findModel(name: String?): JblModelInfo? {
        val entry = com.soniccontrol.core.devices.DeviceCatalog(entries).findByName(name) ?: return null
        return models.firstOrNull { it.name == entry.model }
    }

    fun findByPid(pid: Int): JblModelInfo? = models.firstOrNull { pid in it.pids }
}
