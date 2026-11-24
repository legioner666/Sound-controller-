package com.soniccontrol.drivers.jbl

import com.soniccontrol.core.devices.CommandGuard
import com.soniccontrol.core.devices.CommandKind
import com.soniccontrol.core.devices.DeviceCommand
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.ResponseMatcher
import com.soniccontrol.core.devices.SupportStatus
import com.soniccontrol.core.protocol.u8

/** Every frame the JBL driver can build. Anything else is refused by [JblGuard]. */
object JblCommands {
    private fun p3(match: (JblProtocol.P3.Frame) -> Boolean) = ResponseMatcher { b -> JblProtocol.P3.parse(b)?.let(match) ?: false }
    private fun race(match: (JblProtocol.Race.Frame) -> Boolean) = ResponseMatcher { b -> JblProtocol.Race.parse(b)?.let(match) ?: false }

    fun p3Version(s: SupportStatus) = DeviceCommand("jbl.aa.version", CommandKind.READ, s, JblProtocol.P3.versionGet(), 2000,
        p3(JblProtocol.P3::isVersionReply))
    fun p3EnableNotifications(s: SupportStatus) = DeviceCommand("jbl.aa.notify-on", CommandKind.WRITE, s, JblProtocol.P3.enableNotifications(), 1500,
        p3(JblProtocol.P3::isNotifyAck))
    fun p3Battery(s: SupportStatus) = DeviceCommand("jbl.aa.battery", CommandKind.READ, s, JblProtocol.P3.batteryGet(), 1500,
        p3(JblProtocol.P3::isBatteryReport))
    fun p3ModeGet(s: SupportStatus) = DeviceCommand("jbl.aa.mode.get", CommandKind.READ, s, JblProtocol.P3.listeningModeGet(), 1500,
        p3(JblProtocol.P3::isListeningModeReport))
    fun p3ModeSet(s: SupportStatus, mode: NoiseMode) = DeviceCommand("jbl.aa.mode.set.${mode.name.lowercase()}", CommandKind.WRITE, s,
        JblProtocol.P3.listeningModeSet(mode), 2000, p3 { JblProtocol.P3.parseListeningMode(it) == mode })

    fun p4Get(s: SupportStatus, ids: List<Int>) = DeviceCommand("jbl.p4.get.${ids.joinToString("-")}", CommandKind.READ, s,
        JblProtocol.P4.getDeviceInfo(ids), 2500, ResponseMatcher { JblProtocol.P4.looksLikeP4(it) })

    fun raceModeGet(s: SupportStatus) = DeviceCommand("jbl.race.mode.get", CommandKind.READ, s, JblProtocol.Race.modeGet(), 1500,
        race { it.type == JblProtocol.Race.TYPE_REPLY && JblProtocol.Race.isModeReport(it) })
    fun raceModeSet(s: SupportStatus, mode: NoiseMode) = DeviceCommand("jbl.race.mode.set.${mode.name.lowercase()}", CommandKind.WRITE, s,
        JblProtocol.Race.modeSet(mode), 2000, race { it.type == JblProtocol.Race.TYPE_REPLY && it.command == JblProtocol.Race.CMD_MODE_SET })

    /** Exact byte sequences that may leave the phone. */
    val allowedFrames: List<ByteArray> = buildList {
        add(JblProtocol.P3.versionGet()); add(JblProtocol.P3.enableNotifications()); add(JblProtocol.P3.batteryGet())
        add(JblProtocol.P3.listeningModeGet()); NoiseMode.entries.forEach { add(JblProtocol.P3.listeningModeSet(it)) }
        add(JblProtocol.Race.modeGet()); NoiseMode.entries.forEach { add(JblProtocol.Race.modeSet(it)) }
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
