package com.soniccontrol.core.devices

/**
 * Drivers for brands without an implemented protocol yet: they recognise the catalog models and
 * provide the universal features, so the UI can say exactly what is and is not available.
 */
open class CatalogOnlyDriver(
    override val id: String,
    override val brand: Brand,
    override val catalog: List<CatalogEntry>,
    private val namePrefixes: List<String>,
    private val protocolNote: String,
) : HeadphoneDriver {
    override val displayName: String get() = brand.displayName

    override fun match(identity: DeviceIdentity): DriverMatch? {
        val name = identity.name?.replace(Regex("\\s*\\([0-9A-Fa-f]{4}\\)$"), "")?.trim() ?: return null
        catalog.firstOrNull { it.model.equals(name, true) || it.fullName.equals(name, true) || it.aliases.any { a -> a.equals(name, true) } }
            ?.let { return DriverMatch(80, it, "catalog name match") }
        if (namePrefixes.any { name.startsWith(it, ignoreCase = true) }) return DriverMatch(40, null, "brand name prefix")
        return null
    }

    override fun createSession(link: DeviceLink, identity: DeviceIdentity, env: DriverEnvironment): DriverSession =
        object : BaseDriverSession(
            link, identity, env, id,
            HeadphoneState(
                displayName = identity.name ?: identity.address,
                catalogEntry = match(identity)?.entry,
                protocol = protocolNote,
                notes = listOf("${brand.displayName}: vendor protocol not implemented yet; universal features only"),
                notices = setOf(Notice.VENDOR_NOT_IMPLEMENTED),
            ),
        ) {
            override suspend fun start() = startUniversalSources()
        }
}
