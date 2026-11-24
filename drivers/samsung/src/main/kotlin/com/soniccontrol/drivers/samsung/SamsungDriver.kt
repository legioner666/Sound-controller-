package com.soniccontrol.drivers.samsung

import com.soniccontrol.core.devices.Brand
import com.soniccontrol.core.devices.CatalogEntry
import com.soniccontrol.core.devices.CatalogNote
import com.soniccontrol.core.devices.CatalogOnlyDriver
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.SupportStatus

/**
 * Samsung: model recognition and universal features. The vendor protocol is on the roadmap
 * (docs/ROADMAP.md); until then nothing is sent to these headsets.
 */
class SamsungDriver : CatalogOnlyDriver(ID, Brand.SAMSUNG, ENTRIES, listOf("Galaxy Buds", "Samsung"), PROTOCOL) {
    companion object {
        const val ID = "samsung"
        private const val PROTOCOL = "Samsung Galaxy Buds RFCOMM (planned: independent implementation; GalaxyBudsClient is GPL-3.0 and is used for protocol facts only)"
        private val BASIC_FEATURES = mapOf(
            Feature.CONNECTION_STATUS to SupportStatus.BASIC,
            Feature.SYSTEM_VOLUME to SupportStatus.BASIC,
            Feature.MEDIA_CONTROL to SupportStatus.BASIC,
            Feature.BATTERY to SupportStatus.SUPPORTED,
        )
        val ENTRIES = listOf(
        CatalogEntry(Brand.SAMSUNG, "Galaxy Buds3 Pro", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Samsung Galaxy Buds3 Pro"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SAMSUNG, "Galaxy Buds3", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Samsung Galaxy Buds3"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SAMSUNG, "Galaxy Buds2 Pro", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Samsung Galaxy Buds2 Pro"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SAMSUNG, "Galaxy Buds2", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Samsung Galaxy Buds2"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SAMSUNG, "Galaxy Buds FE", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Samsung Galaxy Buds FE"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SAMSUNG, "Galaxy Buds Pro", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Samsung Galaxy Buds Pro"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SAMSUNG, "Galaxy Buds Live", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Samsung Galaxy Buds Live"), note = CatalogNote.PLANNED_DRIVER),
        CatalogEntry(Brand.SAMSUNG, "Galaxy Buds+", ID, PROTOCOL, BASIC_FEATURES, aliases = listOf("Samsung Galaxy Buds+"), note = CatalogNote.PLANNED_DRIVER),
        )
    }
}
