package com.terraformation.backend.tracking

import com.terraformation.backend.tracking.PlantingSiteMapWriterFixtures.expectedFile
import com.terraformation.backend.tracking.PlantingSiteMapWriterFixtures.layers
import com.terraformation.backend.tracking.PlantingSiteMapWriterFixtures.objectMapper
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlantingSiteGeoJsonWriterTest {
  @Test
  fun `writes one GeoJSON feature collection per layer with right-hand-rule winding`() {
    val output = ByteArrayOutputStream()
    val expected =
        setOf("site-boundary.geojson", "strata.geojson", "substrata.geojson", "exclusions.geojson")
            .associateWith { filename -> expectedFile(filename).use { objectMapper.readTree(it) } }

    PlantingSiteGeoJsonWriter(objectMapper).write(layers, output)
    val actual =
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
          generateSequence { zip.nextEntry }
              .associate { entry -> entry.name to objectMapper.readTree(zip.readBytes()) }
        }

    assertEquals(expected, actual, "GeoJSON archive contents")
  }
}
