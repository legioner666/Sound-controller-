package com.soniccontrol.drivers.soundcore

import com.soniccontrol.core.devices.Brand
import com.soniccontrol.core.devices.CatalogEntry
import com.soniccontrol.core.devices.CatalogNote
import com.soniccontrol.core.devices.CatalogOnlyDriver
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.SupportStatus

/**
 * Soundcore: model recognition and universal features. The vendor protocol is on the roadmap
 * (docs/ROADMAP.md); until then nothing is sent to these headsets.
 */
class SoundcoreDriver : CatalogOnlyDriver(ID, Brand.SOUNDCORE, ENTRIES, listOf("Soundcore", "Liberty", "Space Q45", "Life Q"), PROTOCOL) {
    companion object {
        const val ID = "soundcore"
        private const val PROTOCOL = "Soundcore protocol (planned: port from Earshot, MIT)"
        private val BASIC_FEATURES = mapOf(
            Feature.CONNECTION_STATUS to SupportStatus.BASIC,
            Feature.SYSTEM_VOLUME to SupportStatus.BASIC,
            Feature.MEDIA_CONTROL to SupportStatus.BASIC,
            Feature.BATTERY to SupportStatus.SUPPORTED,
        )
        val ENTRIES = listOf(
        CatalogEntry(Brand.SOUNDCORE, "Liberty 4 NC", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Soundcore Liberty 4 NC"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SOUNDCORE, "Liberty 4", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Soundcore Liberty 4"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SOUNDCORE, "Liberty 5", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Soundcore Liberty 5"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SOUNDCORE, "Space Q45", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Soundcore Space Q45"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SOUNDCORE, "Space One", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Soundcore Space One"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SOUNDCORE, "Life Q30", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Soundcore Life Q30"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SOUNDCORE, "P40i", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Soundcore P40i"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SOUNDCORE, "Sleep A30", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Soundcore Sleep A30"), note = CatalogNote.PLANNED_DRIVER),
        )
    }
}
