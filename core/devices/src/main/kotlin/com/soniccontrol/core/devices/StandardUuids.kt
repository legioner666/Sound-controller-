package com.soniccontrol.core.devices

import java.util.UUID

object StandardUuids {
    fun sig(short: Int): UUID =
        UUID.fromString("0000" + short.toString(16).padStart(4, '0') + "-0000-1000-8000-00805f9b34fb")

    val BATTERY_SERVICE: UUID = sig(0x180F)
    val BATTERY_LEVEL: UUID = sig(0x2A19)
    val DEVICE_INFORMATION: UUID = sig(0x180A)
    val MANUFACTURER_NAME: UUID = sig(0x2A29)
    val MODEL_NUMBER: UUID = sig(0x2A24)
    val FIRMWARE_REVISION: UUID = sig(0x2A26)
    val CCCD: UUID = sig(0x2902)
    val FAST_PAIR_SERVICE: UUID = sig(0xFE2C)
    /** Google Fast Pair Message Stream, RFCOMM. */
    val FAST_PAIR_MESSAGE_STREAM: UUID = UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c")
    val SPP: UUID = sig(0x1101)
    val A2DP_SINK: UUID = sig(0x110B)
    val HANDSFREE: UUID = sig(0x111E)

    private val names = mapOf(
        BATTERY_SERVICE to "Battery Service", BATTERY_LEVEL to "Battery Level",
        DEVICE_INFORMATION to "Device Information", MANUFACTURER_NAME to "Manufacturer Name",
        MODEL_NUMBER to "Model Number", FIRMWARE_REVISION to "Firmware Revision", CCCD to "CCCD",
        FAST_PAIR_SERVICE to "Google Fast Pair", FAST_PAIR_MESSAGE_STREAM to "Fast Pair Message Stream",
        SPP to "Serial Port", A2DP_SINK to "A2DP Sink", HANDSFREE to "Hands-Free",
        sig(0x1800) to "Generic Access", sig(0x1801) to "Generic Attribute", sig(0x2A00) to "Device Name",
        sig(0x2A01) to "Appearance", sig(0x2A05) to "Service Changed", sig(0x110E) to "AVRCP",
        sig(0x110C) to "AVRCP Target", sig(0x1108) to "Headset", sig(0x112F) to "Phonebook Access",
        sig(0x1200) to "PnP Information", sig(0x184E) to "Audio Stream Control", sig(0x1850) to "Published Audio Capabilities",
    )

    /** Extra names contributed by drivers (vendor services). */
    private val extra = java.util.concurrent.ConcurrentHashMap<UUID, String>()

    fun register(uuid: UUID, name: String) { extra[uuid] = name }

    fun describe(uuid: UUID): String? = names[uuid] ?: extra[uuid]
}
