package com.terraformation.backend.tracking.model

import kotlin.io.path.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.MultiPolygon

internal class ShapefileTest {
  private val resourcesDir = "src/test/resources/tracking"

  @Test
  fun `can read zipfile with multiple shapefiles`() {
    val shapefiles = Shapefile.fromZipFile(Path("$resourcesDir/TwoShapefiles.zip"))

    assertEquals(2, shapefiles.size, "Number of shapefiles loaded")

    val plantingSite = shapefiles.firstOrNull { it.features.size == 1 }
    assertNotNull(plantingSite, "Should have found site boundary shapefile")
    assertEquals(
        MultiPolygon::class.java,
        plantingSite!!.features[0].rawGeometry.javaClass,
        "Site feature type",
    )
  }

  @Test
  fun `throws exception if secondary files are missing`() {
    assertThrows<IllegalArgumentException> {
      Shapefile.fromFiles(Path("$resourcesDir/BareShapefile.shp"))
    }
  }
}
