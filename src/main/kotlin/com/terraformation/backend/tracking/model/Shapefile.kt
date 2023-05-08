package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.SRID
import com.terraformation.backend.file.useAndDelete
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.extension
import org.geotools.data.DataStoreFinder
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.geotools.referencing.operation.projection.TransverseMercator
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.opengis.feature.simple.SimpleFeature
import org.opengis.filter.Filter
import org.opengis.referencing.FactoryException
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

/** Simplified representation of the geometry data from a shapefile. */
data class Shapefile(
    val typeName: String,
    val features: List<ShapefileFeature>,
) {
  companion object {
    /** Reads all the shapefiles from a zipfile. */
    fun fromZipFile(zipFilePath: Path): List<Shapefile> {
      return createTempDirectory().useAndDelete { tempDir ->
        // Unpack the zipfile to a temporary directory since the shapefile reader needs to be able
        // to open secondary files in the same directory as the main shapefile.
        ZipFile(zipFilePath.toFile()).use { zipFile ->
          zipFile.stream().forEach { entry ->
            zipFile.getInputStream(entry).use { inputStream ->
              val destinationPath = tempDir.resolve(entry.name.substringAfterLast('/'))
              Files.copy(inputStream, destinationPath)
            }
          }
        }

        fromDirectory(tempDir)
      }
    }

    /** Reads all the shapefiles in a directory. */
    private fun fromDirectory(directory: Path): List<Shapefile> {
      return Files.find(
              directory, 1, { path, _ -> path.extension.equals("shp", ignoreCase = true) })
          .use { path -> path.map { fromFiles(it) }.toList() }
          .toList()
    }

    /** Reads data from a single shapefile and its secondary files. */
    fun fromFiles(shapefilePath: Path): Shapefile {
      val dataStoreParams = mapOf("url" to shapefilePath.toUri().toURL())
      val dataStore = DataStoreFinder.getDataStore(dataStoreParams)

      if (dataStore.typeNames.size != 1) {
        throw IllegalArgumentException(
            "Expected shapefile to have 1 datatype; found ${dataStore.typeNames.size}",
        )
      }

      val features =
          dataStore
              .getFeatureSource(dataStore.typeNames[0])
              .getFeatures(Filter.INCLUDE)
              .features()
              .use { features ->
                generateSequence { if (features.hasNext()) features.next() else null }
                    .map { ShapefileFeature.fromGeotools(it) }
                    .toList()
              }

      return Shapefile(dataStore.typeNames[0], features)
    }
  }
}
