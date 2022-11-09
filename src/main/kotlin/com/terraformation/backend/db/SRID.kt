package com.terraformation.backend.db

import org.opengis.referencing.crs.CoordinateReferenceSystem

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

  fun byCRS(crs: CoordinateReferenceSystem): Int {
    return when (val name = "${crs.name}") {
      "WGS_1984_Web_Mercator_Auxiliary_Sphere",
      "WGS 84 / Pseudo-Mercator" -> SPHERICAL_MERCATOR
      "WGS 84" -> LONG_LAT
      else -> throw IllegalArgumentException("Unrecognized coordinate reference system $name")
    }
  }
}
