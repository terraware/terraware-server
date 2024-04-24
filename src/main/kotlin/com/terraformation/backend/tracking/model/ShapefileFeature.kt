package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.SRID
import com.terraformation.backend.util.calculateAreaHectares
import java.math.BigDecimal
import org.geotools.api.feature.simple.SimpleFeature
import org.geotools.api.referencing.crs.CoordinateReferenceSystem
import org.locationtech.jts.geom.Geometry

/** Simplified representation of the data about a single feature from a shapefile. */
data class ShapefileFeature(
    val geometry: Geometry,
    val properties: Map<String, String>,
    val coordinateReferenceSystem: CoordinateReferenceSystem,
) {
  /** Looks up a list of properties by name and returns the first value that exists. */
  fun getProperty(names: Collection<String>): String? {
    return names.firstNotNullOfOrNull { properties[it] }
  }

  /** Returns true if the feature has any of a number of properties. */
  fun hasProperty(names: Collection<String>): Boolean = names.any { it in properties }

  /**
   * Calculates the approximate area of a geometry in hectares. If this feature isn't already in a
   * UTM coordinate system, converts it to the appropriate one first.
   *
   * @throws FactoryException The geometry couldn't be converted to UTM.
   */
  fun calculateAreaHectares(geom: Geometry = this.geometry): BigDecimal {
    return geom.calculateAreaHectares(coordinateReferenceSystem)
  }

  companion object {
    fun fromGeotools(feature: SimpleFeature): ShapefileFeature {
      val defaultGeometry = feature.defaultGeometry as Geometry
      val crs =
          feature.defaultGeometryProperty.type.coordinateReferenceSystem
              ?: throw IllegalArgumentException("Feature didn't have a coordinate reference system")
      defaultGeometry.srid = SRID.byCRS(crs)

      val properties =
          feature.properties
              .filter { property ->
                // Ignore non-scalar properties; we only want labels such as zone and plot names.
                property.type.binding == String::class.java ||
                    Number::class.java.isAssignableFrom(property.type.binding)
              }
              .associate { "${it.name}" to "${it.value}" }

      return ShapefileFeature(defaultGeometry, properties, crs)
    }
  }
}
