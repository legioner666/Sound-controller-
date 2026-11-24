package com.soniccontrol.drivers.generic

import com.soniccontrol.core.devices.BaseDriverSession
import com.soniccontrol.core.devices.Brand
import com.soniccontrol.core.devices.CatalogEntry
import com.soniccontrol.core.devices.DeviceIdentity
import com.soniccontrol.core.devices.DeviceLink
import com.soniccontrol.core.devices.DriverEnvironment
import com.soniccontrol.core.devices.DriverMatch
import com.soniccontrol.core.devices.DriverSession
import com.soniccontrol.core.devices.HeadphoneDriver
import com.soniccontrol.core.devices.HeadphoneState

/**
 * Universal driver for any Bluetooth headset. It never writes to the headset: it reads the standard
 * BLE Battery Service and listens to the Fast Pair Message Stream when the headset offers them.
 */
class GenericDriver : HeadphoneDriver {
    override val id = ID
    override val brand = Brand.OTHER
    override val displayName = "Generic"
    override val catalog: List<CatalogEntry> = emptyList()

    override fun match(identity: DeviceIdentity) = DriverMatch(1, null, "no brand driver matched; universal features only")

    override fun createSession(link: DeviceLink, identity: DeviceIdentity, env: DriverEnvironment): DriverSession =
        Session(link, identity, env)

    private class Session(link: DeviceLink, identity: DeviceIdentity, env: DriverEnvironment) :
        BaseDriverSession(link, identity, env, ID, HeadphoneState(displayName = identity.name ?: identity.address, protocol = "Universal")) {
        override suspend fun start() = startUniversalSources()
    }

    companion object {
        const val ID = "generic"
    }
}

