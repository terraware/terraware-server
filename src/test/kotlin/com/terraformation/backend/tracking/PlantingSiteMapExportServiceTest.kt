package com.terraformation.backend.tracking

import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.polygon
import com.terraformation.backend.polygonWithHoles
import com.terraformation.backend.tracking.db.PlantingSiteHistoryIncompleteException
import com.terraformation.backend.tracking.db.PlantingSiteMapIncompleteException
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.plantingSiteStore.BasePlantingSiteStoreTest
import com.terraformation.backend.util.calculateAreaHectares
import io.mockk.every
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.util.zip.ZipInputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.algorithm.Orientation
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import tools.jackson.module.kotlin.jacksonMapperBuilder

internal class PlantingSiteMapExportServiceTest : BasePlantingSiteStoreTest() {
  private val objectMapper = jacksonMapperBuilder().addModule(GeometryModule()).build()
  private val service by lazy { PlantingSiteMapExportService(objectMapper, store) }

  @Test
  fun `exports the requested snapshot geometry and attributes with the current site name`() {
    val snapshotBoundary = multiPolygon(0.5)
    val stratum = multiPolygon(0.4)
    val substratum = multiPolygon(0.3)
    val exclusion = multiPolygon(0.1)

    insertPlantingSite(
        areaHa = BigDecimal(100),
        boundary = multiPolygon(1),
        insertHistory = false,
        name = "Current site name",
    )
    insertStratum(insertHistory = false, name = "Live stratum")
    insertSubstratum(insertHistory = false, name = "Live substratum")

    val historyId =
        insertPlantingSiteHistory(
            areaHa = BigDecimal(50),
            boundary = snapshotBoundary,
            exclusion = exclusion,
        )
    insertStratumHistory(areaHa = BigDecimal(40), boundary = stratum, name = "Saved stratum")
    insertSubstratumHistory(
        areaHa = BigDecimal(30),
        boundary = substratum,
        name = "Saved substratum",
    )

    val simplifiedBoundary = multiPolygon(0.2)
    insertSimplifiedPlantingSiteHistory(boundary = simplifiedBoundary, exclusion = null)
    insertSimplifiedStratumHistory(boundary = simplifiedBoundary)
    insertSimplifiedSubstratumHistory(boundary = simplifiedBoundary)

    insertPlantingSiteHistory(
        boundary = multiPolygon(0.9),
        createdTime = clock.instant().plusSeconds(1),
    )
    insertStratumHistory()
    insertSubstratumHistory()

    val expected =
        listOf(
            namedLayer("site-boundary", snapshotBoundary, "Current site name", 50),
            namedLayer("strata", stratum, "Saved stratum", 40),
            namedLayer("substrata", substratum, "Saved substratum", 30),
            exclusionLayer(exclusion),
        )

    val actual = service.fetchLayers(inserted.plantingSiteId, historyId)

    assertEquals(expected, actual, "Unsimplified snapshot layers")
  }

  @Test
  fun `exports current layers independently of saved snapshots`() {
    val liveBoundary = multiPolygon(1)
    val stratum = multiPolygon(0.8)
    val substratum = multiPolygon(0.6)
    val exclusion = multiPolygon(0.2)

    insertPlantingSite(
        areaHa = BigDecimal(100),
        boundary = liveBoundary,
        exclusion = exclusion,
        insertHistory = false,
        name = "Current site",
    )
    insertStratum(
        areaHa = BigDecimal(80),
        boundary = stratum,
        insertHistory = false,
        name = "Current stratum",
    )
    insertSubstratum(
        areaHa = BigDecimal(60),
        boundary = substratum,
        insertHistory = false,
        name = "Current substratum",
    )

    val simplifiedBoundary = multiPolygon(0.4)
    insertSimplifiedPlantingSite(boundary = simplifiedBoundary, exclusion = null)
    insertSimplifiedStratum(boundary = simplifiedBoundary)
    insertSimplifiedSubstratum(boundary = simplifiedBoundary)

    val expected =
        listOf(
            namedLayer("site-boundary", liveBoundary, "Current site", 100),
            namedLayer("strata", stratum, "Current stratum", 80),
            namedLayer("substrata", substratum, "Current substratum", 60),
            exclusionLayer(exclusion),
        )

    val layersWithoutHistory = service.fetchCurrentLayers(inserted.plantingSiteId)

    assertEquals(expected, layersWithoutHistory, "Live layers without history")

    insertPlantingSiteHistory(boundary = multiPolygon(0.5))
    insertStratumHistory()
    insertSubstratumHistory()

    val layersWithHistory = service.fetchCurrentLayers(inserted.plantingSiteId)

    assertEquals(expected, layersWithHistory, "Live layers with history")
  }

  @Test
  fun `rejects a snapshot with an empty boundary`() {
    val boundary = multiPolygon(1)
    insertPlantingSite(areaHa = BigDecimal.TEN, boundary = boundary)
    insertPlantingSiteHistory(boundary = boundary.factory.createMultiPolygon())

    assertThrows<PlantingSiteHistoryIncompleteException> {
      service.fetchLayers(inserted.plantingSiteId, inserted.plantingSiteHistoryId)
    }
  }

  @Test
  fun `rejects a snapshot with a missing area`() {
    insertPlantingSite(areaHa = BigDecimal.TEN, boundary = multiPolygon(1))
    insertPlantingSiteHistory(areaHa = null)

    assertThrows<PlantingSiteHistoryIncompleteException> {
      service.fetchLayers(inserted.plantingSiteId, inserted.plantingSiteHistoryId)
    }
  }

  @Test
  fun `rejects a current map with a missing boundary`() {
    insertPlantingSite(areaHa = BigDecimal.TEN, x = null)

    assertThrows<PlantingSiteMapIncompleteException> {
      service.fetchCurrentLayers(inserted.plantingSiteId)
    }
  }

  @Test
  fun `rejects a current map with a missing area`() {
    insertPlantingSite(areaHa = null, boundary = multiPolygon(1))

    assertThrows<PlantingSiteMapIncompleteException> {
      service.fetchCurrentLayers(inserted.plantingSiteId)
    }
  }

  @Test
  fun `rejects a current map without permission to read the site`() {
    insertPlantingSite(areaHa = BigDecimal.TEN, boundary = multiPolygon(1))
    every { user.canReadPlantingSite(inserted.plantingSiteId) } returns false

    assertThrows<PlantingSiteNotFoundException> {
      service.fetchCurrentLayers(inserted.plantingSiteId)
    }
  }

  @Test
  fun `downloads each requested snapshot with only name and area attributes`() {
    val clockwiseBoundary =
        polygonWithHoles(polygon(200).reverse(), listOf(polygon(20, 20, 30, 30)))
    val exclusion = multiPolygon(10)
    val siteId =
        insertPlantingSite(
            areaHa = BigDecimal(100),
            boundary = multiPolygon(clockwiseBoundary),
            exclusion = exclusion,
            name = "Terraware",
        )
    val oldId = inserted.plantingSiteHistoryId
    insertStratum(name = "Old stratum", stableId = "stratum-key")
    insertSubstratum(name = "Old substratum", plantingCompletedTime = Instant.EPOCH)

    val fullBoundary = multiPolygon(250)
    val currentId =
        insertPlantingSiteHistory(boundary = fullBoundary, createdTime = Instant.ofEpochSecond(1))
    insertStratumHistory(name = "New stratum")
    insertSubstratumHistory(name = "New substratum")
    insertSimplifiedPlantingSiteHistory(boundary = multiPolygon(125))

    fun download(historyId: PlantingSiteHistoryId) =
        ByteArrayOutputStream().let { output ->
          val layers = service.fetchLayers(siteId, historyId)
          service.write(PlantingSiteMapFormat.GeoJson, layers, output)
          ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            buildMap {
              generateSequence { zip.nextEntry }
                  .forEach { entry ->
                    put(entry.name, objectMapper.readTree(zip.readBytes()))
                  }
            }
          }
        }

    val old = download(oldId)
    val current = download(currentId)
    assertEquals(
        setOf("site-boundary.geojson", "strata.geojson", "substrata.geojson", "exclusions.geojson"),
        old.keys,
        "GeoJSON archive entries",
    )
    val oldGeometry = old.getValue("site-boundary.geojson")["features"][0]["geometry"]
    val oldBoundary = objectMapper.treeToValue(oldGeometry, MultiPolygon::class.java)
    val oldPolygon = oldBoundary.getGeometryN(0) as Polygon
    assertThat(Orientation.isCCW(oldPolygon.exteriorRing.coordinateSequence))
        .describedAs("Exterior ring is counterclockwise")
        .isTrue()
    assertThat(Orientation.isCCW(oldPolygon.getInteriorRingN(0).coordinateSequence))
        .describedAs("Interior ring is clockwise")
        .isFalse()
    val oldStratum = old.getValue("strata.geojson")["features"][0]["properties"]
    val oldSubstratum = old.getValue("substrata.geojson")["features"][0]["properties"]
    assertEquals("Old stratum", oldStratum["name"].stringValue(), "Historical stratum name")
    assertEquals(
        "Old substratum",
        oldSubstratum["name"].stringValue(),
        "Historical substratum name",
    )
    val oldBoundaryProperties = old.getValue("site-boundary.geojson")["features"][0]["properties"]
    assertEquals(
        "Terraware",
        oldBoundaryProperties["name"].stringValue(),
        "Current site name on old map",
    )
    val exclusionProperties = old.getValue("exclusions.geojson")["features"][0]["properties"]
    assertEquals(
        setOf("area_ha"),
        exclusionProperties.propertyNames().toSet(),
        "Exclusion attributes",
    )
    assertEquals(
        exclusion.calculateAreaHectares().toDouble(),
        exclusionProperties["area_ha"].asDouble(),
        "Exclusion area in hectares",
    )

    val currentBoundary = current.getValue("site-boundary.geojson")["features"][0]
    val boundaryProperties = currentBoundary["properties"]
    assertEquals("Terraware", boundaryProperties["name"].stringValue(), "Current site name")
    assertEquals(100, boundaryProperties["area_ha"].asInt(), "Site area in hectares")
    val expectedGeometry = objectMapper.readTree(objectMapper.writeValueAsBytes(fullBoundary))
    assertEquals(expectedGeometry, currentBoundary["geometry"], "Unsimplified site boundary")
    val currentStratum = current.getValue("strata.geojson")["features"][0]["properties"]
    assertEquals(
        "New stratum",
        currentStratum["name"].stringValue(),
        "Latest snapshot stratum name",
    )
    val currentSubstratum = current.getValue("substrata.geojson")["features"][0]["properties"]
    listOf(
            oldBoundaryProperties,
            oldStratum,
            oldSubstratum,
            boundaryProperties,
            currentStratum,
            currentSubstratum,
        )
        .forEach {
          assertEquals(
              setOf("name", "area_ha"),
              it.propertyNames().toSet(),
              "Named feature attributes",
          )
        }
  }

  private fun namedLayer(layer: String, boundary: MultiPolygon, name: String, areaHa: Int) =
      PlantingSiteMapLayer(
          layer,
          listOf(
              PlantingSiteMapFeature(
                  boundary,
                  mapOf("name" to name, "area_ha" to BigDecimal(areaHa)),
              )
          ),
      )

  private fun exclusionLayer(exclusion: MultiPolygon) =
      PlantingSiteMapLayer(
          "exclusions",
          listOf(
              PlantingSiteMapFeature(
                  exclusion,
                  mapOf("area_ha" to exclusion.calculateAreaHectares()),
              )
          ),
      )
}
