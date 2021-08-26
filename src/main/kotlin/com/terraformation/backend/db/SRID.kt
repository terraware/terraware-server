package com.terraformation.backend.db

/**
 * Spatial reference identifiers used when querying geographic coordinates from the database. Every
 * geometric value has an associated SRID.
 */
object SRID {
  /** Longitude and latitude as used by GPS. [Reference.](https://epsg.io/4326) */
  const val LONG_LAT = 4326

  /**
   * Pseudo-Mercator or Spherical Mercator as used by mapping software.
   * [Reference.](https://epsg.io/3857)
   */
  const val SPHERICAL_MERCATOR = 3857
}
