package com.soniccontrol.drivers.bose

import com.soniccontrol.core.devices.Brand
import com.soniccontrol.core.devices.CatalogEntry
import com.soniccontrol.core.devices.CatalogOnlyDriver
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.SupportStatus

/**
 * Bose: model recognition and universal features. The vendor protocol is on the roadmap
 * (docs/ROADMAP.md); until then nothing is sent to these headsets.
 */
class BoseDriver : CatalogOnlyDriver(ID, Brand.BOSE, ENTRIES, listOf("Bose", "QC"), PROTOCOL) {
    companion object {
        const val ID = "bose"
        private const val PROTOCOL = "Bose BMAP (planned: port from Earshot, MIT)"
        private const val NOTE = "Universal features now; vendor controls planned."
        private val BASIC_FEATURES = mapOf(
            Feature.CONNECTION_STATUS to SupportStatus.BASIC,
            Feature.SYSTEM_VOLUME to SupportStatus.BASIC,
            Feature.MEDIA_CONTROL to SupportStatus.BASIC,
            Feature.BATTERY to SupportStatus.SUPPORTED,
        )
        val ENTRIES = listOf(
        CatalogEntry(Brand.BOSE, "QuietComfort Ultra Headphones", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Bose QuietComfort Ultra Headphones"), notes = NOTE),
        CatalogEntry(Brand.BOSE, "QuietComfort Ultra Earbuds", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Bose QuietComfort Ultra Earbuds"), notes = NOTE),
        CatalogEntry(Brand.BOSE, "QuietComfort 45", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Bose QuietComfort 45"), notes = NOTE),
        CatalogEntry(Brand.BOSE, "QuietComfort Earbuds II", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Bose QuietComfort Earbuds II"), notes = NOTE),
        CatalogEntry(Brand.BOSE, "Noise Cancelling Headphones 700", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Bose Noise Cancelling Headphones 700"), notes = NOTE),
        CatalogEntry(Brand.BOSE, "QuietComfort 35 II", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Bose QuietComfort 35 II"), notes = NOTE),
        )
    }
}
