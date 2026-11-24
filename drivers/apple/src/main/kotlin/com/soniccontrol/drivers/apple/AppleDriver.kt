package com.soniccontrol.drivers.apple

import com.soniccontrol.core.devices.BaseDriverSession
import com.soniccontrol.core.devices.BatteryLevel
import com.soniccontrol.core.devices.BatteryStatus
import com.soniccontrol.core.devices.Brand
import com.soniccontrol.core.devices.CatalogEntry
import com.soniccontrol.core.devices.DataSource
import com.soniccontrol.core.devices.DeviceIdentity
import com.soniccontrol.core.devices.DeviceLink
import com.soniccontrol.core.devices.DriverEnvironment
import com.soniccontrol.core.devices.DriverMatch
import com.soniccontrol.core.devices.DriverSession
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.HeadphoneDriver
import com.soniccontrol.core.devices.HeadphoneState
import com.soniccontrol.core.devices.SupportStatus
import com.soniccontrol.core.diagnostics.EventCategory

/**
 * AirPods and Beats: battery from Apple's proximity-pairing advertisement (passive BLE scan).
 * Nothing is ever sent. Apple's control channel (AAP over L2CAP) has no public Android API and is not used.
 */
class AppleDriver : HeadphoneDriver {
    override val id = "apple"
    override val brand = Brand.APPLE
    override val displayName = "Apple"

    private val names = listOf(
        "AirPods", "AirPods (2nd generation)", "AirPods (3rd generation)", "AirPods 4", "AirPods Pro",
        "AirPods Pro (2nd generation)", "AirPods Pro 3", "AirPods Max", "Beats Studio Buds", "Beats Studio Buds+",
        "Beats Fit Pro", "Beats Solo Pro", "Beats Studio Pro", "Powerbeats Pro", "Beats Flex",
    )

    override val catalog: List<CatalogEntry> = names.map {
        CatalogEntry(
            Brand.APPLE, it, id, "Apple proximity advert (BLE, receive-only)",
            mapOf(
                Feature.CONNECTION_STATUS to SupportStatus.BASIC, Feature.SYSTEM_VOLUME to SupportStatus.BASIC,
                Feature.MEDIA_CONTROL to SupportStatus.BASIC, Feature.BATTERY to SupportStatus.EXPERIMENTAL,
                Feature.BATTERY_PER_COMPONENT to SupportStatus.EXPERIMENTAL,
            ),
            notes = "Battery in 10 % steps. Noise control needs Apple's private channel and is not available on Android.",
        )
    }

    override fun match(identity: DeviceIdentity): DriverMatch? {
        val n = identity.name ?: return null
        catalog.firstOrNull { it.model.equals(n, true) }?.let { return DriverMatch(85, it, "catalog name match") }
        if (n.contains("AirPods", true) || n.contains("Beats", true)) return DriverMatch(60, null, "AirPods/Beats in name")
        return null
    }

    override fun createSession(link: DeviceLink, identity: DeviceIdentity, env: DriverEnvironment): DriverSession =
        object : BaseDriverSession(link, identity, env, id, HeadphoneState(displayName = identity.name ?: "AirPods", catalogEntry = match(identity)?.entry, protocol = "Apple proximity advert")) {
            override suspend fun start() {
                startUniversalSources()
                launch {
                    link.manufacturerAdverts(ContinuityAdvert.APPLE_COMPANY_ID).collect { ad ->
                        val parsed = ContinuityAdvert.parse(ad.data) ?: return@collect
                        // A stranger's AirPods nearby must not be shown as ours.
                        if (!AppleModels.fitsName(parsed.model, identity.name)) return@collect
                        if (ad.rssi < -75) return@collect
                        log(EventCategory.RX, "proximity advert model 0x${parsed.model.toString(16)} rssi ${ad.rssi}", ad.data)
                        val now = env.clock()
                        val status = if (parsed.isHeadphones) {
                            BatteryStatus(single = BatteryLevel(parsed.headlinePercent), source = DataSource.APPLE_CONTINUITY_ADVERT, timestampMillis = now)
                        } else {
                            BatteryStatus(
                                left = BatteryLevel(parsed.leftPercent, parsed.leftCharging),
                                right = BatteryLevel(parsed.rightPercent, parsed.rightCharging),
                                case = BatteryLevel(parsed.casePercent, parsed.caseCharging),
                                source = DataSource.APPLE_CONTINUITY_ADVERT, timestampMillis = now,
                            )
                        }
                        acceptBattery(status)
                    }
                }
            }
        }
}
