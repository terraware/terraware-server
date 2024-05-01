package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.SRID
import org.geotools.api.feature.simple.SimpleFeature
import org.geotools.api.referencing.crs.CoordinateReferenceSystem
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel

/** Simplified representation of the data about a single feature from a shapefile. */
data class ShapefileFeature(
    /** Original geometry from shapefile; may be in any coordinate reference system. */
    val rawGeometry: Geometry,
    val properties: Map<String, String>,
    val coordinateReferenceSystem: CoordinateReferenceSystem,
) {
  /** Looks up a list of properties by name and returns the first value that exists. */
  fun getProperty(names: Collection<String>): String? {
    return names.firstNotNullOfOrNull { properties[it] }
  }

  /** Returns true if the feature has any of a number of properties. */
  fun hasProperty(names: Collection<String>): Boolean = names.any { it in properties }

  /** Geometry in longitude/latitude coordinates. */
  val geometry: Geometry by lazy {
    if (coordinateReferenceSystem == longLatCrs) {
      rawGeometry
    } else {
      val transform = CRS.findMathTransform(coordinateReferenceSystem, longLatCrs)
      JTS.transform(rawGeometry, transform).also { it.srid = SRID.LONG_LAT }
    }
  }

  companion object {
    val longLatCrs: CoordinateReferenceSystem by lazy { CRS.decode("EPSG:${SRID.LONG_LAT}", true) }

    fun fromGeotools(feature: SimpleFeature): ShapefileFeature {
      val crs =
          feature.defaultGeometryProperty.type.coordinateReferenceSystem
              ?: throw IllegalArgumentException("Feature didn't have a coordinate reference system")
      val geometry =
          GeometryFactory(PrecisionModel(), SRID.byCRS(crs))
              .createGeometry(feature.defaultGeometry as Geometry)

      val properties =
          feature.properties
              .filter { property ->
                // Ignore non-scalar properties; we only want labels such as zone and plot names.
                property.type.binding == String::class.java ||
                    Number::class.java.isAssignableFrom(property.type.binding)
              }
              .associate { "${it.name}" to "${it.value}" }

      return ShapefileFeature(geometry, properties, crs)
    }
  }
}
