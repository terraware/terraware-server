package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.SRID
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.geotools.referencing.operation.projection.TransverseMercator
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.crs.CoordinateReferenceSystem

/** Simplified representation of the data about a single feature from a shapefile. */
data class ShapefileFeature(
    val geometry: Geometry,
    val properties: Map<String, String>,
    val coordinateReferenceSystem: CoordinateReferenceSystem,
) {
  /**
   * Calculates the approximate area of a shapefile feature in hectares. If the feature isn't
   * already in a UTM coordinate system, converts it to the appropriate one first.
   *
   * @throws FactoryException The feature couldn't be converted to UTM.
   */
  fun calculateAreaHectares(): Double {
    // Transform feature to UTM if it isn't already.
    val utmGeometry =
        if (CRS.getProjectedCRS(coordinateReferenceSystem) is TransverseMercator) {
          this.geometry
        } else {
          // To use the "look up the right UTM for a location" feature of GeoTools, we need to
          // know the location in WGS84 (EPSG:4326) longitude and latitude; transform the feature's
          // centroid coordinates to WGS84.
          val wgs84Centroid =
              if (CRS.lookupEpsgCode(coordinateReferenceSystem, false) == SRID.LONG_LAT) {
                geometry.centroid
              } else {
                val wgs84Transform =
                    CRS.findMathTransform(coordinateReferenceSystem, DefaultGeographicCRS.WGS84)
                JTS.transform(geometry.centroid, wgs84Transform) as Point
              }

          val utmCrs = CRS.decode("AUTO2:42001,${wgs84Centroid.x},${wgs84Centroid.y}")

          JTS.transform(geometry, CRS.findMathTransform(coordinateReferenceSystem, utmCrs))
        }

    return utmGeometry.area / SQUARE_METERS_PER_HECTARE
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
