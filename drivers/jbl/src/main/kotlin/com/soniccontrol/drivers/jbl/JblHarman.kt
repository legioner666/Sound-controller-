package com.soniccontrol.drivers.jbl

import com.soniccontrol.core.devices.EarSide
import com.soniccontrol.core.devices.GestureAction
import com.soniccontrol.core.devices.GestureLayout
import com.soniccontrol.core.devices.GestureSlot
import com.soniccontrol.core.devices.NoiseLevelKind
import com.soniccontrol.core.devices.SideGestures
import com.soniccontrol.core.devices.TapType
import com.soniccontrol.core.protocol.Hex
import com.soniccontrol.core.protocol.bytesOf
import com.soniccontrol.core.protocol.u8

/**
 * H-AA settings of the JBL Tune Beam 2 family (TASK 014). Pure codecs, no Bluetooth.
 *
 * Sources (see docs/JBL_GESTURE_PROTOCOL.md): our own JBL Tune Beam 2 captures (B484FB, fw 0.2.8.0: AA 91 and AA 06), a
 * published PacketLogger capture of the JBL app with a Tune Beam 2 (jbl-on-mac, issue #39 / PR #40: AA 77, AA 91 levels, AA 06
 * features, AA 80, AA A2), and the model profile of the JBL app (PID 0x210D, gesture groups, Smart Audio & Video values).
 * Only byte layouts present in a Tune Beam 2 capture are used; nothing is ported from other models. No code was copied.
 */
object JblHarman {

    // ------------------------------------------------------------------ touch controls (AA 77)

    const val CMD_GESTURE = 0x77
    private const val GESTURE_SET = 0x00
    private const val GESTURE_REPORT = 0x02
    private const val GESTURE_QUERY = 0x01

    /** Slot ids per earbud, in tap order. Tap meaning is inferred from the JBL app's layout table (see protocol doc). */
    private val SLOTS = mapOf(EarSide.LEFT to listOf(0x06, 0x07, 0x08, 0x0C), EarSide.RIGHT to listOf(0x09, 0x0A, 0x0B, 0x0E))
    private val TAPS = listOf(TapType.SINGLE, TapType.DOUBLE, TapType.TRIPLE, TapType.HOLD)

    /** The four complete layouts the JBL app writes on a Tune Beam 2, as captured. Nothing else is ever sent. */
    private fun layoutActions(side: EarSide, layout: GestureLayout): List<Int> = when (layout) {
        GestureLayout.AMBIENT_SOUND -> listOf(if (side == EarSide.LEFT) 0x09 else 0x0B, 0x04, 0x00, 0xA1)
        GestureLayout.VOLUME -> listOf(0x01, 0x02, 0x00, 0xA1)
        GestureLayout.PLAYBACK -> listOf(0x08, 0x05, 0x06, 0xA1)
        GestureLayout.NONE -> listOf(0x00, 0x00, 0x00, 0x00)
    }

    /** Action byte meanings, matching the JBL app's layout table for this model. 0x09/0x0B: the ANC/Ambient cycle of the left/right bud. */
    private fun actionOf(b: Int): GestureAction? = when (b) {
        0x00 -> GestureAction.NONE
        0x01 -> GestureAction.VOLUME_UP
        0x02 -> GestureAction.VOLUME_DOWN
        0x04 -> GestureAction.TALK_THRU
        0x05 -> GestureAction.NEXT_TRACK
        0x06 -> GestureAction.PREVIOUS_TRACK
        0x08 -> GestureAction.PLAY_PAUSE
        0x09, 0x0B -> GestureAction.NOISE_CYCLE
        0xA1 -> GestureAction.VOICE_ASSISTANT
        else -> null
    }

    fun gestureQuery(side: EarSide?): ByteArray = bytesOf(0xAA, CMD_GESTURE, 0x02, GESTURE_QUERY, when (side) { null -> 0xFF; EarSide.LEFT -> 0xFD; EarSide.RIGHT -> 0xFE })

    fun gestureSet(side: EarSide, layout: GestureLayout): ByteArray {
        val pairs = SLOTS.getValue(side).zip(layoutActions(side, layout)).flatMap { (s, a) -> listOf(s, a) }
        return bytesOf(0xAA, CMD_GESTURE, 0x09, GESTURE_SET, *pairs.toIntArray())
    }

    /**
     * `AA 77 <len> 02 (<slot> <action>)*`, one or both earbuds, every slot of a side present exactly once, no other slot ids.
     * Returns null for anything else (including our own write echoed back with `00`).
     */
    fun parseGestureReport(frame: ByteArray): Map<EarSide, SideGestures>? {
        if (frame.size < 4 || frame.u8(0) != 0xAA || frame.u8(1) != CMD_GESTURE || frame.u8(2) != frame.size - 3 || frame.u8(3) != GESTURE_REPORT) return null
        val body = frame.size - 4
        if (body % 2 != 0 || body !in setOf(8, 16)) return null
        val values = LinkedHashMap<Int, Int>()
        var i = 4
        while (i < frame.size) { if (values.put(frame.u8(i), frame.u8(i + 1)) != null) return null; i += 2 }
        val out = LinkedHashMap<EarSide, SideGestures>()
        for ((side, slots) in SLOTS) {
            val present = slots.count { it in values }
            if (present == 0) continue
            if (present != slots.size) return null
            val actions = slots.map { values.getValue(it) }
            val layout = GestureLayout.entries.firstOrNull { layoutActions(side, it) == actions }
            val raw = Hex.encode(slots.zip(actions).flatMap { (s, a) -> listOf(s, a) }.map { it.toByte() }.toByteArray())
            out[side] = SideGestures(layout, TAPS.zip(actions).map { (tap, a) -> GestureSlot(tap, actionOf(a)) }, raw)
        }
        if (values.keys.any { k -> SLOTS.values.none { k in it } }) return null
        return out.takeIf { it.isNotEmpty() }
    }

    /** `AA 77 09 00 ...`: the headset echoing a write. Not a report and never a confirmation. */
    fun isGestureEcho(frame: ByteArray): Boolean = frame.size == 12 && frame.u8(0) == 0xAA && frame.u8(1) == CMD_GESTURE && frame.u8(2) == 9 && frame.u8(3) == GESTURE_SET

    // ------------------------------------------------------------------ noise-control levels (AA 91 2x)

    const val LEVEL_MAX = 7
    private const val LEVEL_ID_ANC = 0x04
    private const val LEVEL_ID_AMBIENT = 0xA1

    fun levelSet(kind: NoiseLevelKind, level: Int): ByteArray {
        require(level in 1..LEVEL_MAX) { "level out of range" }
        return bytesOf(0xAA, 0x91, 0x03, 0x20, if (kind == NoiseLevelKind.ANC) LEVEL_ID_ANC else LEVEL_ID_AMBIENT, level)
    }

    /** Level query `AA 91 01 21`. Not seen in a capture (the JBL app's level screen was not recorded); Lab only, Experimental. */
    fun levelQuery(): ByteArray = bytesOf(0xAA, 0x91, 0x01, 0x21)

    /** `AA 91 <len> 22 (<id> <1..7>)+`: levels report. Unknown ids or values out of range make the frame invalid. */
    fun parseLevelReport(frame: ByteArray): Map<NoiseLevelKind, Int>? {
        if (frame.size < 6 || frame.u8(0) != 0xAA || frame.u8(1) != 0x91 || frame.u8(2) != frame.size - 3 || frame.u8(3) != 0x22 || (frame.size - 4) % 2 != 0) return null
        val out = HashMap<NoiseLevelKind, Int>()
        var i = 4
        while (i < frame.size) {
            val kind = when (frame.u8(i)) { LEVEL_ID_ANC -> NoiseLevelKind.ANC; LEVEL_ID_AMBIENT -> NoiseLevelKind.AMBIENT; else -> return null }
            val v = frame.u8(i + 1)
            if (v !in 1..LEVEL_MAX || out.put(kind, v) != null) return null
            i += 2
        }
        return out
    }

    /** ANC level carried by the listening-mode report (`... 04 <1..7>`), seen in both captures. Null when absent or out of range. */
    fun ancLevelInModeReport(f: JblProtocol.P3.Frame): Int? =
        JblProtocol.P3.unknownSlotValue(f)?.takeIf { it in 1..LEVEL_MAX }

    // ------------------------------------------------------------------ feature records (AA 06), read-only

    const val FEATURE_SPATIAL = 0x0C
    const val FEATURE_VOICE_AWARE = 0x0D
    const val FEATURE_VOLUME_LIMITER = 0x20

    /** `AA 06 <len16 LE> 01 (<id16 BE>)+`. Only ids on the read allowlist. */
    fun featureQuery(vararg ids: Int): ByteArray {
        require(ids.isNotEmpty() && ids.all { it in READABLE_FEATURES })
        val body = byteArrayOf(0x01) + ids.flatMap { listOf((it shr 8).toByte(), it.toByte()) }
        return bytesOf(0xAA, 0x06, body.size and 0xFF, body.size shr 8) + body
    }

    val READABLE_FEATURES = setOf(FEATURE_SPATIAL, FEATURE_VOICE_AWARE, FEATURE_VOLUME_LIMITER)

    /** `AA 06 <len16> 02 (<id16 BE> <len8> <value>)+` with exact length. Null for anything else. */
    fun parseFeatureReport(frame: ByteArray): Map<Int, ByteArray>? {
        val f = JblProtocol.P3.parse(frame) ?: return null
        if (f.command != JblProtocol.P3.CMD_NATIVE || f.payload.isEmpty() || f.payload.u8(0) != 0x02) return null
        val out = LinkedHashMap<Int, ByteArray>()
        var i = 1
        while (i < f.payload.size) {
            if (i + 3 > f.payload.size) return null
            val id = f.payload.u8(i) shl 8 or f.payload.u8(i + 1)
            val len = f.payload.u8(i + 2)
            if (i + 3 + len > f.payload.size || out.put(id, f.payload.copyOfRange(i + 3, i + 3 + len)) != null) return null
            i += 3 + len
        }
        return out
    }

    /** Spatial sound: `01 <mode>`, 1 = off, 3 = head tracking (both seen); 2 is listed by the source app but not captured. */
    fun spatialOf(v: ByteArray): String? = if (v.size == 2 && v.u8(0) == 0x01) when (v.u8(1)) { 1 -> "off"; 2 -> "on"; 3 -> "head_tracking"; else -> null } else null
    /** VoiceAware: `<on 0|1> <level 1..3>`. */
    fun voiceAwareOf(v: ByteArray): String? =
        if (v.size == 2 && v.u8(0) <= 1 && v.u8(1) in 1..3) (if (v.u8(0) == 1) "on" else "off") + ", level " + v.u8(1) else null
    fun limiterOf(v: ByteArray): String? = if (v.size == 1 && v.u8(0) <= 1) (if (v.u8(0) == 1) "on" else "off") else null

    // ------------------------------------------------------------------ Smart Audio & Video (AA 80), read-only

    fun smartAudioQuery(): ByteArray = bytesOf(0xAA, 0x80, 0x01, 0x01)

    /**
     * `AA 80 09 02 <4 x u16 LE>`. The three values match the JBL app's model profile for the Tune Beam 2
     * (normal 197/46/280, audio 256/46/280, video 197/46/120) and the capture; the fourth (FFFF) is ignored.
     */
    fun parseSmartAudio(frame: ByteArray): String? {
        if (frame.size != 12 || frame.u8(0) != 0xAA || frame.u8(1) != 0x80 || frame.u8(2) != 9 || frame.u8(3) != 0x02) return null
        fun v(i: Int) = frame.u8(i) or (frame.u8(i + 1) shl 8)
        return when (Triple(v(4), v(6), v(8))) {
            Triple(197, 46, 280) -> "normal"
            Triple(256, 46, 280) -> "audio"
            Triple(197, 46, 120) -> "video"
            else -> null
        }
    }

    // ------------------------------------------------------------------ hardware EQ (AA A2), read-only

    /** `AA A2 02 01 FF`: current EQ. Reply `AA A2 74 00 04 <preset> ...` (120 bytes, needs the MTU the app requests). */
    fun eqQuery(): ByteArray = bytesOf(0xAA, 0xA2, 0x02, 0x01, 0xFF)

    /** Preset ids seen with this model's EQ frame. 0x67 (custom) is reported by the source app as the user curve. */
    private val EQ_PRESETS = mapOf(0 to "off", 1 to "club", 2 to "vocal", 3 to "bass", 6 to "jazz", 7 to "studio", 9 to "extreme_bass", 0x67 to "custom")

    fun parseEqReport(frame: ByteArray): String? {
        val f = JblProtocol.P3.parse(frame) ?: return null
        if (f.command != 0xA2 || f.payload.size != 0x74 || f.payload.u8(0) != 0x04) return null
        return EQ_PRESETS[f.payload.u8(1)]
    }

    // ------------------------------------------------------------------ frame labels for the Lab

    /** Short technical label of an H-AA frame (Lab timeline, HCI import). Never claims more than the decoders do. */
    fun describe(frame: ByteArray): String? {
        if (frame.size < 3 || frame.u8(0) != 0xAA) return null
        JblProtocol.P3.errorReplyFor(frame)?.let { return "error reply for 0x%02x".format(it) }
        parseGestureReport(frame)?.let { m -> return "touch controls report: " + m.entries.joinToString { "${it.key} ${it.value.layout ?: "unknown"}" } }
        if (isGestureEcho(frame)) return "touch controls write (echo)"
        parseLevelReport(frame)?.let { return "levels report: $it" }
        parseSmartAudio(frame)?.let { return "smart audio & video: $it" }
        parseEqReport(frame)?.let { return "EQ preset: $it" }
        JblProtocol.P3.validate(frame)?.let { f ->
            JblProtocol.P3.parseListeningMode(f)?.let { return "listening mode: $it" + (ancLevelInModeReport(f)?.let { l -> ", ANC level $l" } ?: "") }
            JblProtocol.P3.parseNativeBattery(f)?.let { return "battery records: $it" }
            parseFeatureReport(frame)?.let { return "feature report ids " + it.keys.joinToString { k -> "0x%02x".format(k) } }
            return "H-AA 0x%02x".format(f.command)
        }
        return when (frame.u8(1)) {
            CMD_GESTURE -> "touch controls (unrecognised layout)"
            0x91 -> "listening mode / level (unrecognised)"
            else -> "H-AA 0x%02x (not decoded)".format(frame.u8(1))
        }
    }
}
