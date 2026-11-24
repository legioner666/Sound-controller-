package com.soniccontrol.core.devices

/**
 * State of one communication channel. Only [CONFIRMED] means the other side proved it is there:
 * for GATT, services were discovered; for a vendor control channel, a fully validated frame arrived.
 */
enum class ChannelState {
    /** The device or driver does not offer this channel. */
    UNAVAILABLE,
    OPENING,
    /** Transport is open but nothing has been validated yet. */
    OPEN,
    CONFIRMED,
    FAILED,
    CLOSED,
    ;

    val isUp: Boolean get() = this == OPEN || this == CONFIRMED
}

/** Audio connection as reported by Android's A2DP/headset profiles. */
enum class AudioLinkState { UNKNOWN, CONNECTED, DISCONNECTED }

/** Every channel shown separately, so "connected" is never claimed for a channel nobody confirmed. */
data class LinkStatus(
    val audio: AudioLinkState = AudioLinkState.UNKNOWN,
    val gatt: ChannelState = ChannelState.UNAVAILABLE,
    val rfcomm: ChannelState = ChannelState.UNAVAILABLE,
    val control: ChannelState = ChannelState.UNAVAILABLE,
) {
    /** True only when the vendor control channel answered with a valid frame. */
    val controlConfirmed: Boolean get() = control == ChannelState.CONFIRMED
}

/** User-facing notices raised by drivers. Localised in the UI; never free text. */
enum class Notice {
    /** Another app (usually the manufacturer's) may hold the control channel. */
    CLOSE_VENDOR_APP,
    /** The driver knows the brand but found no control channel on this device. */
    NO_CONTROL_CHANNEL,
    /** A control channel exists, but its protocol variant is not identified yet. */
    PROTOCOL_UNIDENTIFIED,
    /** The vendor protocol for this brand is not implemented yet. */
    VENDOR_NOT_IMPLEMENTED,
    /** Battery comes only from standard sources (Fast Pair / BLE Battery Service). */
    STANDARD_BATTERY_ONLY,
    /** Experimental commands are available but switched off in Settings. */
    EXPERIMENTAL_OFF,
}

/** Catalog annotations, localised in the UI. */
enum class CatalogNote { PRIORITY_TEST_DEVICE, CONFIRMED_ON_HARDWARE, PLANNED_DRIVER, TEN_PERCENT_STEPS_NO_CONTROL }
