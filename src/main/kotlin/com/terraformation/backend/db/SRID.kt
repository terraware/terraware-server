package com.terraformation.backend.db

import java.util.Properties
import org.geotools.api.referencing.crs.CoordinateReferenceSystem

/**
 * Spatial reference identifiers used when querying geographic coordinates from the database. Every
 * geometric value has an associated SRID.
 */
object SRID {
  /** Longitude and latitude as used by GPS. [Reference.](https://epsg.io/4326) */
  const val LONG_LAT = 4326

  /**
   * WGS 1984 Web Mercator or Spherical Mercator as used by mapping software.
   * [Reference.](https://epsg.io/3857)
   */
  const val SPHERICAL_MERCATOR = 3857

  /**
   * Universal Transverse Mercator zone 20S (in South America). Like all UTM zones, this has the
   * property that 1 unit in the coordinate space is 1 meter on the ground. We use this SRID for
   * testing; other UTM zones are looked up dynamically since a zone only covers a small slice of
   * the planet.
   */
  const val UTM_20S = 32620

  val mapping: Map<String, Int> by lazy { loadMapping() }

  /**
   * Returns the SRID for a coordinate system based on its name.
   *
   * @throws IllegalArgumentException The coordinate system had an unknown name.
   */
  fun byCRS(crs: CoordinateReferenceSystem): Int {
    return byName("${crs.name}")
  }

  /**
   * Returns the SRID for a coordinate system name. Ignores any `EPSG:` prefix on the name.
   *
   * @throws IllegalArgumentException The name was unknown.
   */
  fun byName(name: String): Int {
    return mapping[name.substringAfter("EPSG:")]
        ?: throw IllegalArgumentException("Unrecognized coordinate reference system $name")
  }

  private fun loadMapping(): Map<String, Int> {
    val properties = Properties()

    javaClass.getResourceAsStream("/gis/srid.properties").use { properties.load(it) }

    return properties.entries.associate { (key, value) -> "$key" to "$value".toInt() }
  }
}
