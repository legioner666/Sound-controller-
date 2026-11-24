package com.soniccontrol.core.devices

/** Per-feature compatibility status, exactly as presented to users. */
enum class SupportStatus {
    /** Physically tested on this model by the project. */
    VERIFIED,
    /** Implemented from a protocol confirmed on real hardware of the same family; not yet tested on this model. */
    SUPPORTED,
    /** Built from protocol research only. Off unless the user enables experimental features. */
    EXPERIMENTAL,
    /** Provided by Android itself (connection, volume, media keys), works for any headset. */
    BASIC,
    /** Not available. The control is hidden or disabled. */
    UNSUPPORTED,
}

/** Overall tier, derived automatically from the feature statuses. */
enum class SupportLevel { UNIVERSAL, ADVANCED, FULL }

enum class Feature(val advanced: Boolean) {
    CONNECTION_STATUS(false),
    SYSTEM_VOLUME(false),
    MEDIA_CONTROL(false),
    BATTERY(false),
    BATTERY_PER_COMPONENT(true),
    NOISE_CONTROL(true),
    TALK_THRU(true),
    DEVICE_EQUALIZER(true),
    TOUCH_GESTURES(true),
    SPATIAL_AUDIO(true),
    FIND_DEVICE(true),
    MULTIPOINT(true),
    WEAR_DETECTION(true),
}

data class FeatureSupport(val status: SupportStatus, val note: String = "")

/** Declarative capability set of one connected device. Anything not listed is [SupportStatus.UNSUPPORTED]. */
data class DeviceCapabilities(val features: Map<Feature, FeatureSupport>) {

    fun of(feature: Feature): FeatureSupport = features[feature] ?: FeatureSupport(SupportStatus.UNSUPPORTED)

    /** True when the control may be used right now. Experimental features need the user's opt-in. */
    fun usable(feature: Feature, experimentalEnabled: Boolean): Boolean = when (of(feature).status) {
        SupportStatus.VERIFIED, SupportStatus.SUPPORTED, SupportStatus.BASIC -> true
        SupportStatus.EXPERIMENTAL -> experimentalEnabled
        SupportStatus.UNSUPPORTED -> false
    }

    val level: SupportLevel
        get() {
            val advanced = features.filterKeys { it.advanced }.values.map { it.status }
                .filter { it != SupportStatus.UNSUPPORTED && it != SupportStatus.BASIC }
            return when {
                advanced.isEmpty() -> SupportLevel.UNIVERSAL
                advanced.all { it == SupportStatus.VERIFIED } -> SupportLevel.FULL
                else -> SupportLevel.ADVANCED
            }
        }

    fun with(feature: Feature, support: FeatureSupport) = DeviceCapabilities(features + (feature to support))

    companion object {
        /** What Android gives for any connected headset, with no vendor protocol at all. */
        val UNIVERSAL = DeviceCapabilities(
            mapOf(
                Feature.CONNECTION_STATUS to FeatureSupport(SupportStatus.BASIC, "Android Bluetooth"),
                Feature.SYSTEM_VOLUME to FeatureSupport(SupportStatus.BASIC, "Android media volume"),
                Feature.MEDIA_CONTROL to FeatureSupport(SupportStatus.BASIC, "Android media keys"),
            ),
        )

        fun universalPlus(vararg extra: Pair<Feature, FeatureSupport>) = DeviceCapabilities(UNIVERSAL.features + extra)
    }
}
