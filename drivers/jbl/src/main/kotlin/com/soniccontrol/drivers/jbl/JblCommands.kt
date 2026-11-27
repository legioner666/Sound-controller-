package com.soniccontrol.drivers.jbl

import com.soniccontrol.core.devices.CommandGuard
import com.soniccontrol.core.devices.CommandKind
import com.soniccontrol.core.devices.DeviceCommand
import com.soniccontrol.core.devices.EarSide
import com.soniccontrol.core.devices.GestureLayout
import com.soniccontrol.core.devices.NoiseLevelKind
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.ResponseMatcher
import com.soniccontrol.core.devices.SupportStatus
import com.soniccontrol.core.protocol.u8

/** Every frame the JBL driver can build. Anything else is refused by [JblGuard]. */
object JblCommands {
    private fun p3(match: (JblProtocol.P3.Frame) -> Boolean) = ResponseMatcher { b -> JblProtocol.P3.validate(b)?.let(match) ?: false }
    private fun race(match: (JblProtocol.Race.Frame) -> Boolean) = ResponseMatcher { b -> JblProtocol.Race.validate(b)?.let(match) ?: false }

    fun p3Version(s: SupportStatus) = DeviceCommand("jbl.aa.version", CommandKind.READ, s, JblProtocol.P3.versionGet(), 2000,
        p3(JblProtocol.P3::isVersionReply))
    /** `AA EE 01 <cmd>` echoing the command we just sent: the headset answered, but not with the expected report. */
    private fun errorFor(cmd: Int) = ResponseMatcher { b -> JblProtocol.P3.errorReplyFor(b) == cmd }

    fun p3EnableNotifications(s: SupportStatus) = DeviceCommand("jbl.aa.notify-on", CommandKind.WRITE, s, JblProtocol.P3.enableNotifications(), 1500,
        p3(JblProtocol.P3::isNotifyAck), negativeMatcher = errorFor(JblProtocol.P3.CMD_NOTIFY_CONTROL))
    fun p3Battery(s: SupportStatus) = DeviceCommand("jbl.aa.battery", CommandKind.READ, s, JblProtocol.P3.batteryGet(), 1500,
        p3(JblProtocol.P3::isBatteryReport), negativeMatcher = errorFor(JblProtocol.P3.CMD_BATTERY))
    fun p3ModeGet(s: SupportStatus) = DeviceCommand("jbl.aa.mode.get", CommandKind.READ, s, JblProtocol.P3.listeningModeGet(), 1500,
        p3(JblProtocol.P3::isListeningModeReport))
    fun p3ModeSet(s: SupportStatus, mode: NoiseMode) = DeviceCommand("jbl.aa.mode.set.${mode.name.lowercase()}", CommandKind.WRITE, s,
        JblProtocol.P3.listeningModeSet(mode), 2000, p3 { JblProtocol.P3.parseListeningMode(it) == mode },
        negativeMatcher = errorFor(JblProtocol.P3.CMD_LISTENING_MODE))

    /** Reply must be a valid Protocol 4 packet carrying at least one requested id (or the first packet of a multi-packet reply). */
    fun p4Get(s: SupportStatus, ids: List<Int>) = DeviceCommand("jbl.p4.get.${ids.joinToString("-")}", CommandKind.READ, s,
        JblProtocol.P4.getDeviceInfo(ids), 2500, ResponseMatcher { b ->
            val p = JblProtocol.P4.validate(b) ?: return@ResponseMatcher false
            if (p.count > 1) p.index == 0 else JblProtocol.P4.parseTlv(p.payload).keys.any { it in ids }
        })

    fun raceModeGet(s: SupportStatus) = DeviceCommand("jbl.race.mode.get", CommandKind.READ, s, JblProtocol.Race.modeGet(), 1500,
        race { it.type == JblProtocol.Race.TYPE_REPLY && JblProtocol.Race.isModeReport(it) })
    fun raceModeSet(s: SupportStatus, mode: NoiseMode) = DeviceCommand("jbl.race.mode.set.${mode.name.lowercase()}", CommandKind.WRITE, s,
        JblProtocol.Race.modeSet(mode), 2000, race { it.type == JblProtocol.Race.TYPE_REPLY && it.command == JblProtocol.Race.CMD_MODE_SET })

    // ---- TASK 014: Tune Beam 2 settings. Always EXPERIMENTAL until the owner confirms them on the headset. ----

    private val anyGestureFrame = ResponseMatcher { b -> JblHarman.parseGestureReport(b) != null || JblHarman.isGestureEcho(b) }

    fun gestureQuery(side: EarSide?) = DeviceCommand("jbl.aa.gesture.get.${side?.name?.lowercase() ?: "both"}", CommandKind.READ, SupportStatus.EXPERIMENTAL,
        JblHarman.gestureQuery(side), 2000, ResponseMatcher { b ->
            val m = JblHarman.parseGestureReport(b) ?: return@ResponseMatcher false
            if (side == null) m.size == 2 else side in m
        })

    /** The reply to a write is not trusted as the result: the session always reads both earbuds back. */
    fun gestureSet(side: EarSide, layout: GestureLayout) = DeviceCommand("jbl.aa.gesture.set.${side.name.lowercase()}.${layout.name.lowercase()}",
        CommandKind.WRITE, SupportStatus.EXPERIMENTAL, JblHarman.gestureSet(side, layout), 1500, anyGestureFrame,
        negativeMatcher = errorFor(JblHarman.CMD_GESTURE))

    fun levelSet(kind: NoiseLevelKind, level: Int) = DeviceCommand("jbl.aa.level.set.${kind.name.lowercase()}.$level", CommandKind.WRITE, SupportStatus.EXPERIMENTAL,
        JblHarman.levelSet(kind, level), 1500, ResponseMatcher { b -> b.size >= 2 && b.u8(0) == 0xAA && b.u8(1) == 0x91 && JblProtocol.P3.validate(b) != null },
        negativeMatcher = errorFor(JblProtocol.P3.CMD_LISTENING_MODE))

    fun levelQuery() = DeviceCommand("jbl.aa.level.get", CommandKind.READ, SupportStatus.EXPERIMENTAL, JblHarman.levelQuery(), 1500,
        ResponseMatcher { JblHarman.parseLevelReport(it) != null }, negativeMatcher = errorFor(JblProtocol.P3.CMD_LISTENING_MODE))

    fun featureQuery(id: Int) = DeviceCommand("jbl.aa.feature.get.0x%02x".format(id), CommandKind.READ, SupportStatus.EXPERIMENTAL,
        JblHarman.featureQuery(id), 1500, ResponseMatcher { JblHarman.parseFeatureReport(it)?.containsKey(id) == true },
        negativeMatcher = errorFor(JblProtocol.P3.CMD_NATIVE))

    fun smartAudioQuery() = DeviceCommand("jbl.aa.smartaudio.get", CommandKind.READ, SupportStatus.EXPERIMENTAL, JblHarman.smartAudioQuery(), 1500,
        ResponseMatcher { b -> b.size == 12 && b.u8(0) == 0xAA && b.u8(1) == 0x80 && b.u8(3) == 0x02 }, negativeMatcher = errorFor(0x80))

    fun eqQuery() = DeviceCommand("jbl.aa.eq.get", CommandKind.READ, SupportStatus.EXPERIMENTAL, JblHarman.eqQuery(), 2500,
        ResponseMatcher { b -> JblProtocol.P3.validate(b)?.command == 0xA2 }, negativeMatcher = errorFor(0xA2))

    /** Exact byte sequences that may leave the phone. */
    val allowedFrames: List<ByteArray> = buildList {
        add(JblProtocol.P3.versionGet()); add(JblProtocol.P3.enableNotifications()); add(JblProtocol.P3.batteryGet())
        add(JblProtocol.P3.listeningModeGet()); NoiseMode.entries.forEach { add(JblProtocol.P3.listeningModeSet(it)) }
        add(JblProtocol.Race.modeGet()); NoiseMode.entries.forEach { add(JblProtocol.Race.modeSet(it)) }
        // TASK 014: every frame is a fixed sequence from a Tune Beam 2 capture (or its exact query pattern); no free-form bytes.
        add(JblHarman.gestureQuery(null)); EarSide.entries.forEach { s -> add(JblHarman.gestureQuery(s)); GestureLayout.entries.forEach { add(JblHarman.gestureSet(s, it)) } }
        NoiseLevelKind.entries.forEach { k -> (1..JblHarman.LEVEL_MAX).forEach { add(JblHarman.levelSet(k, it)) } }
        add(JblHarman.levelQuery())
        JblHarman.READABLE_FEATURES.forEach { add(JblHarman.featureQuery(it)) }
        add(JblHarman.smartAudioQuery()); add(JblHarman.eqQuery())
    }
}

/**
 * Raw-bytes check before anything is written. No override exists for the destructive list.
 * Protocol 4 frames pass only as GET_DEVICE_INFO for read-only feature ids.
 */
object JblGuard : CommandGuard {
    override fun refusal(command: DeviceCommand): String? = refusal(command.bytes)

    fun refusal(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return "empty frame"
        if (bytes.size > 32) return "frame longer than 32 bytes"
        if (bytes.u8(0) == JblProtocol.P3.HEADER && bytes.size >= 2 && bytes.u8(1) == JblProtocol.P3.CMD_FACTORY_RESET) {
            return "factory reset is never sent"
        }
        if (JblProtocol.P4.looksLikeP4(bytes)) {
            val p = JblProtocol.P4.parse(bytes) ?: return "malformed Protocol 4 frame"
            if (p.command != JblProtocol.P4.CMD_GET_DEVICE_INFO) return "Protocol 4 command 0x${p.command.toString(16)} is not allowed"
            if (p.payload.size % 2 != 0 || p.payload.isEmpty()) return "malformed Protocol 4 GET"
            val ids = (p.payload.indices step 2).map { (p.payload.u8(it)) or (p.payload.u8(it + 1) shl 8) }
            val bad = ids.filter { it !in JblProtocol.P4.READABLE_IDS }
            return if (bad.isEmpty()) null else "Protocol 4 feature ids $bad are not on the read allowlist"
        }
        if (JblCommands.allowedFrames.none { it.contentEquals(bytes) }) return "frame is not on the JBL allowlist"
        return null
    }
}
