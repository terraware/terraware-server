package com.terraformation.backend.tracking.model

import com.terraformation.backend.file.useAndDelete
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.extension
import org.geotools.data.DataStoreFinder
import org.opengis.filter.Filter

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
