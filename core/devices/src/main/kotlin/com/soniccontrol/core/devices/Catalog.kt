package com.soniccontrol.core.devices

/**
 * One model in the compatibility catalog. [features] lists only what the driver implements;
 * the catalog never claims more than the code can do.
 */
data class CatalogEntry(
    val brand: Brand,
    val model: String,
    val driverId: String,
    val protocol: String,
    val features: Map<Feature, SupportStatus>,
    val aliases: List<String> = emptyList(),
    val notes: String = "",
) {
    val fullName: String get() = if (model.startsWith(brand.displayName, ignoreCase = true)) model else "${brand.displayName} $model"

    val bestStatus: SupportStatus
        get() {
            val advanced = features.filterKeys { it.advanced }.values.toSet()
            return listOf(SupportStatus.VERIFIED, SupportStatus.SUPPORTED, SupportStatus.EXPERIMENTAL)
                .firstOrNull { it in advanced } ?: SupportStatus.BASIC
        }
}

object NameNormalizer {
    /** "LE-JBL TUNE BEAM 3" -> "jbltunebeam3". Removes punctuation, case, and the LE/BLE twin prefix or suffix some buds advertise. */
    fun normalize(name: String): String =
        name.trim().lowercase()
            .replace(Regex("^(le|ble)[-_ ]"), "")
            .replace(Regex("[-_ ](le|ble)$"), "")
            .filter { it.isLetterOrDigit() }
}

class DeviceCatalog(val entries: List<CatalogEntry>) {

    private val byKey: Map<String, CatalogEntry> = buildMap {
        for (e in this@DeviceCatalog.entries) {
            (listOf(e.fullName, e.model) + e.aliases).forEach { putIfAbsent(NameNormalizer.normalize(it), e) }
        }
    }

    /** Exact normalised name match only: "Tune Beam" must never match "Tune Beam 3". */
    fun findByName(name: String?): CatalogEntry? {
        if (name.isNullOrBlank()) return null
        return byKey[NameNormalizer.normalize(name)]
    }

    fun search(query: String): List<CatalogEntry> {
        val q = NameNormalizer.normalize(query)
        if (q.isEmpty()) return entries
        return entries.filter { e -> (listOf(e.fullName) + e.aliases).any { NameNormalizer.normalize(it).contains(q) } }
    }

    fun byBrand(): Map<Brand, List<CatalogEntry>> = entries.groupBy { it.brand }.toSortedMap(compareBy { it.ordinal })
}
