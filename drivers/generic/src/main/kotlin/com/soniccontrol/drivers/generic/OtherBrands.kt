package com.soniccontrol.drivers.generic

import com.soniccontrol.core.devices.Brand
import com.soniccontrol.core.devices.CatalogEntry
import com.soniccontrol.core.devices.CatalogOnlyDriver
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.SupportStatus

/** Target brands without a dedicated driver module yet: recognised by name, universal features only. */
object OtherBrands {
    private val BASIC = mapOf(
        Feature.CONNECTION_STATUS to SupportStatus.BASIC,
        Feature.SYSTEM_VOLUME to SupportStatus.BASIC,
        Feature.MEDIA_CONTROL to SupportStatus.BASIC,
        Feature.BATTERY to SupportStatus.SUPPORTED,
    )

    fun huawei() = CatalogOnlyDriver(
        "huawei", Brand.HUAWEI,
        listOf(
            CatalogEntry(Brand.HUAWEI, "FreeBuds Pro 4", "huawei", "Vendor protocol not implemented", BASIC),
            CatalogEntry(Brand.HUAWEI, "FreeBuds Pro 3", "huawei", "Vendor protocol not implemented", BASIC),
            CatalogEntry(Brand.HUAWEI, "FreeBuds 6i", "huawei", "Vendor protocol not implemented", BASIC),
            CatalogEntry(Brand.HUAWEI, "FreeBuds 5i", "huawei", "Vendor protocol not implemented", BASIC),
        ),
        listOf("HUAWEI FreeBuds", "FreeBuds"), "Vendor protocol not implemented",
    )

    fun oneplus() = CatalogOnlyDriver(
        "oneplus", Brand.ONEPLUS,
        listOf(
            CatalogEntry(Brand.ONEPLUS, "Buds Pro 3", "oneplus", "Vendor protocol not implemented", BASIC),
            CatalogEntry(Brand.ONEPLUS, "Buds Pro 2", "oneplus", "Vendor protocol not implemented", BASIC),
            CatalogEntry(Brand.ONEPLUS, "Buds 3", "oneplus", "Vendor protocol not implemented", BASIC),
            CatalogEntry(Brand.ONEPLUS, "Nord Buds 3 Pro", "oneplus", "Vendor protocol not implemented", BASIC),
        ),
        listOf("OnePlus"), "Vendor protocol not implemented",
    )

    fun sennheiser() = CatalogOnlyDriver(
        "sennheiser", Brand.SENNHEISER,
        listOf(
            CatalogEntry(Brand.SENNHEISER, "MOMENTUM 4", "sennheiser", "Vendor protocol not implemented", BASIC),
            CatalogEntry(Brand.SENNHEISER, "MOMENTUM True Wireless 4", "sennheiser", "Vendor protocol not implemented", BASIC),
            CatalogEntry(Brand.SENNHEISER, "ACCENTUM Plus", "sennheiser", "Vendor protocol not implemented", BASIC),
            CatalogEntry(Brand.SENNHEISER, "MOMENTUM Sport", "sennheiser", "Vendor protocol not implemented", BASIC),
        ),
        listOf("MOMENTUM", "ACCENTUM", "Sennheiser"), "Vendor protocol not implemented",
    )

    fun edifier() = CatalogOnlyDriver(
        "edifier", Brand.EDIFIER,
        listOf(
            CatalogEntry(Brand.EDIFIER, "W820NB Plus", "edifier", "Vendor protocol not implemented", BASIC),
            CatalogEntry(Brand.EDIFIER, "NeoBuds Pro 2", "edifier", "Vendor protocol not implemented", BASIC),
            CatalogEntry(Brand.EDIFIER, "W830NB", "edifier", "Vendor protocol not implemented", BASIC),
            CatalogEntry(Brand.EDIFIER, "Stax Spirit S5", "edifier", "Vendor protocol not implemented", BASIC),
        ),
        listOf("EDIFIER", "NeoBuds"), "Vendor protocol not implemented",
    )

    fun all() = listOf(huawei(), oneplus(), sennheiser(), edifier())
}
