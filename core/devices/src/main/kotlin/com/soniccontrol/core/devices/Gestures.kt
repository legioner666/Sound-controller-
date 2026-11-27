package com.soniccontrol.core.devices

/** One earbud. Touch controls are configured per earbud. */
enum class EarSide { LEFT, RIGHT }

/** Touch gestures as the vendor apps name them. */
enum class TapType { SINGLE, DOUBLE, TRIPLE, HOLD }

/** What a gesture does on the headset itself (firmware action). Phone-side automation never fakes these. */
enum class GestureAction { NONE, PLAY_PAUSE, NEXT_TRACK, PREVIOUS_TRACK, VOLUME_UP, VOLUME_DOWN, NOISE_CYCLE, TALK_THRU, VOICE_ASSISTANT }

/**
 * A whole touch-control layout for one earbud, as the manufacturer's app offers it ("Ambient sound control", "Volume
 * control", ...). Headsets of this kind accept only complete layouts, never single taps: the app therefore offers exactly the
 * layouts the model's profile lists and never composes its own combination.
 */
enum class GestureLayout { NONE, AMBIENT_SOUND, VOLUME, PLAYBACK }

/** One tap of a decoded layout: [action] null = a value the profile does not know (shown as raw, never guessed). */
data class GestureSlot(val tap: TapType, val action: GestureAction?)

/** One earbud as reported by the headset. [layout] null = a combination no known layout matches (left untouched). */
data class SideGestures(val layout: GestureLayout?, val slots: List<GestureSlot>, val rawHex: String)

/** Touch controls read from the headset. Only ever filled from a validated report, never from what the app last sent. */
data class GestureState(val left: SideGestures?, val right: SideGestures?, val source: DataSource, val readAtMillis: Long) {
    fun side(s: EarSide): SideGestures? = if (s == EarSide.LEFT) left else right
}

/**
 * What the connected model allows for touch controls: the layouts per earbud and how sure the protocol is. Absent (null in
 * [HeadphoneState.gestureSupport]) = no gesture protocol for this model; the editor then shows that and nothing else.
 */
data class GestureSupport(
    val layouts: List<GestureLayout>,
    val status: SupportStatus,
    /** Where the command layout comes from (shown in the Lab and the compatibility matrix). */
    val evidence: String,
    /** True when the headset reports touch events by itself (an automation trigger). False = not observed for this model. */
    val reportsTapEvents: Boolean = false,
)

/** Read-only device settings shown in the Lab and the compatibility matrix. Never used to change anything by themselves. */
enum class DeviceSetting { ANC_LEVEL, AMBIENT_LEVEL, EQ_PRESET, SMART_AUDIO_VIDEO, SPATIAL_SOUND, VOICE_AWARE, VOLUME_LIMITER, FAST_PAIR_ANC }

/** One reading: [value] is a short English/technical value ("7", "studio", "audio"); [raw] the frame it was decoded from. */
data class SettingReading(val setting: DeviceSetting, val value: String, val source: DataSource, val atMillis: Long, val raw: String? = null)

/** Noise-control strength kinds with a level (1..[maxLevel]). */
enum class NoiseLevelKind { ANC, AMBIENT }

data class NoiseLevelSupport(val kinds: Set<NoiseLevelKind>, val maxLevel: Int, val status: SupportStatus, val evidence: String)

/** Touch controls saved before the first change, so they can be put back exactly. Kept per headset on the phone. */
data class GestureBackup(val left: GestureLayout?, val right: GestureLayout?, val leftRaw: String?, val rightRaw: String?, val savedAt: Long) {
    fun layout(side: EarSide): GestureLayout? = if (side == EarSide.LEFT) left else right

    /** Only a backup whose both sides are known layouts can be restored: unknown combinations are never sent. */
    val restorable: Boolean get() = left != null && right != null

    companion object {
        fun from(g: GestureState, now: Long): GestureBackup? =
            if (g.left == null || g.right == null) null else GestureBackup(g.left.layout, g.right.layout, g.left.rawHex, g.right.rawHex, now)
    }
}

/**
 * When the app may write touch controls at all (audit of d6f29ed): only if what is on the headset now could be put back.
 * A backup with an unknown combination, or a current reading with one, blocks every write: the app never sends unknown
 * combinations, so it could not restore them.
 */
object GestureWritePolicy {
    enum class Refusal { NOT_READ, UNKNOWN_CURRENT, UNRESTORABLE_BACKUP }

    fun refusal(backup: GestureBackup?, current: GestureState?): Refusal? = when {
        current == null || current.left == null || current.right == null -> Refusal.NOT_READ
        current.left.layout == null || current.right.layout == null -> Refusal.UNKNOWN_CURRENT
        backup != null && !backup.restorable -> Refusal.UNRESTORABLE_BACKUP
        else -> null
    }
}
