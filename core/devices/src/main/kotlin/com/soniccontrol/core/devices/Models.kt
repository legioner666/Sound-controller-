package com.soniccontrol.core.devices

/** Manufacturers in the catalog. Being listed is not a compatibility claim; see the catalog statuses. */
enum class Brand(val displayName: String) {
    JBL("JBL"),
    SONY("Sony"),
    SAMSUNG("Samsung"),
    APPLE("Apple"),
    SOUNDCORE("Soundcore"),
    BOSE("Bose"),
    XIAOMI("Xiaomi"),
    HUAWEI("Huawei"),
    NOTHING("Nothing"),
    ONEPLUS("OnePlus"),
    SENNHEISER("Sennheiser"),
    EDIFIER("Edifier"),
    OTHER("Other"),
}

/** Where a reading came from. Always shown next to the value, so a reading is never presented without its origin. */
enum class DataSource {
    VENDOR_PROTOCOL,
    /** Vendor-native notification decoded from an observed capture, enabled only for the model/firmware it was observed on. */
    VENDOR_NATIVE_EXPERIMENTAL,
    FAST_PAIR_STREAM,
    BLE_BATTERY_SERVICE,
    APPLE_CONTINUITY_ADVERT,
    FAST_PAIR_ADVERT,
}

/** One battery component. [percent] null = the device said "no reading" (e.g. earbud docked). */
/**
 * One battery component. [percent] null = unknown (never shown as 0 %). [lastKnown] means the percentage is carried over
 * from an earlier reading because the latest report did not contain one; [updatedMillis] is when the reading was taken.
 */
data class BatteryLevel(
    val percent: Int?,
    val charging: Boolean? = null,
    val lastKnown: Boolean = false,
    /** When [percent] was read. Independent of [chargingUpdatedMillis]. */
    val updatedMillis: Long? = null,
    /** When [charging] was last reported (null if never reported). */
    val chargingUpdatedMillis: Long? = null,
    /** Which source delivered this component's latest report. */
    val source: DataSource? = null,
) {
    init {
        require(percent == null || percent in 0..100) { "percent out of range: $percent" }
    }
}

data class BatteryStatus(
    val left: BatteryLevel? = null,
    val right: BatteryLevel? = null,
    val case: BatteryLevel? = null,
    /** Headphones with one battery, or single-value sources such as the BLE Battery Service. */
    val single: BatteryLevel? = null,
    val source: DataSource,
    val timestampMillis: Long,
) {
    val hasAnyValue: Boolean
        get() = listOf(left, right, case, single).any { it?.percent != null }

    /** A report may carry only charging flags; they are still news (with their own time). */
    val hasChargingInfo: Boolean
        get() = listOf(left, right, case, single).any { it?.charging != null }

    /** Lowest known level among the listening parts (not the case). */
    val lowestListeningPercent: Int?
        get() = listOfNotNull(left?.percent, right?.percent, single?.percent).minOrNull()
}

enum class NoiseMode { OFF, ANC, AMBIENT, TALK_THRU }

data class NoiseStatus(val mode: NoiseMode, val source: DataSource, val timestampMillis: Long)
