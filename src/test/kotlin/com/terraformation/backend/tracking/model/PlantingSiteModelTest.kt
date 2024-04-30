package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.point
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.toMultiPolygon
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.PrecisionModel

class PlantingSiteModelTest {
  @Nested
  inner class Validate {
    @Test
    fun `checks for maximum envelope area`() {
      val hugeRectangle = makeMultiPolygon { rectangle(200000, 100000) }

      val site = newPlantingSite(boundary = hugeRectangle)

      assertHasProblem(site, "Site must be contained within an envelope.*actual envelope")
    }

    @Test
    fun `checks for duplicate zone names`() {
      val siteBoundary = makeMultiPolygon { square(100) }
      val zone1Boundary = makeMultiPolygon { rectangle(50, 100) }
      val zone2Boundary = makeMultiPolygon {
        east(50)
        square(50)
      }

      val site =
          newPlantingSite(
              boundary = siteBoundary,
              plantingZones =
                  listOf(
                      newPlantingZone(boundary = zone1Boundary, name = "Duplicate"),
                      newPlantingZone(boundary = zone2Boundary, name = "Duplicate"),
                  ))

      assertHasProblem(site, "Zone name Duplicate appears 2 times")
    }

    @Test
    fun `checks for zones not covered by site`() {
      val siteBoundary = makeMultiPolygon { square(100) }
      val zoneBoundary = makeMultiPolygon { square(200) }

      val site =
          newPlantingSite(
              boundary = siteBoundary,
              plantingZones = listOf(newPlantingZone(boundary = zoneBoundary)))

      assertHasProblem(site, "75\\.00% of planting zone .* is not contained")
    }

    @Test
    fun `checks for overlapping zone boundaries`() {
      val siteBoundary = makeMultiPolygon { square(200) }
      val zone1Boundary = makeMultiPolygon { rectangle(100, 200) }
      val zone2Boundary = makeMultiPolygon {
        east(50)
        rectangle(150, 200)
      }

      val site =
          newPlantingSite(
              boundary = siteBoundary,
              plantingZones =
                  listOf(
                      newPlantingZone(boundary = zone1Boundary),
                      newPlantingZone(boundary = zone2Boundary)))

      assertHasProblem(site, "50\\.00% of planting zone Zone 1 overlaps with zone Zone 2")
    }

    @Test
    fun `checks that zones have subzones`() {
      val boundary = makeMultiPolygon { square(100) }

      val site =
          newPlantingSite(
              boundary = boundary, plantingZones = listOf(newPlantingZone(boundary = boundary)))

      assertHasProblem(site, "Planting zone Zone 1 has no subzones")
    }

    @Test
    fun `checks that zones are big enough for observations`() {
      val boundary = makeMultiPolygon { square(50) }

      val site =
          newPlantingSite(
              boundary = boundary,
              plantingZones =
                  listOf(
                      newPlantingZone(
                          boundary = boundary,
                          plantingSubzones = listOf(newPlantingSubzone(boundary = boundary)))))

      assertHasProblem(site, "Planting zone Zone 1 is too small")
    }

    @Test
    fun `checks for subzones not covered by zone`() {
      val siteBoundary = makeMultiPolygon { square(100) }
      val subzoneBoundary = makeMultiPolygon { square(200) }

      val site =
          newPlantingSite(
              boundary = siteBoundary,
              plantingZones =
                  listOf(
                      newPlantingZone(
                          boundary = siteBoundary,
                          plantingSubzones =
                              listOf(newPlantingSubzone(boundary = subzoneBoundary)))))

      assertHasProblem(site, "75\\.00% of planting subzone .* is not contained")
    }

    @Test
    fun `checks for overlapping subzone boundaries`() {
      val siteBoundary = makeMultiPolygon { square(200) }
      val subzone1Boundary = makeMultiPolygon { rectangle(100, 200) }
      val subzone2Boundary = makeMultiPolygon {
        east(50)
        rectangle(150, 200)
      }

      val site =
          newPlantingSite(
              boundary = siteBoundary,
              plantingZones =
                  listOf(
                      newPlantingZone(
                          boundary = siteBoundary,
                          plantingSubzones =
                              listOf(
                                  newPlantingSubzone(boundary = subzone1Boundary),
                                  newPlantingSubzone(boundary = subzone2Boundary)))))

      assertHasProblem(
          site, "50\\.00% of subzone Subzone 1 in zone Zone 1 overlaps with subzone Subzone 2")
    }

    private fun assertHasProblem(site: PlantingSiteModel<*, *, *>, problemRegex: String) {
      val regex = Regex(problemRegex)

      val problems = site.validate()
      assertNotNull(problems, "Validation returned no problems")

      if (problems!!.none { it.contains(regex) }) {
        assertEquals(listOf(problemRegex), problems, "Expected problems list to contain entry")
      }
    }
  }

  private fun makeMultiPolygon(func: Turtle.() -> Unit): MultiPolygon {
    return Turtle(point(0)).makeMultiPolygon(func)
  }

  private var nextZoneNumber = 1
  private var nextSubzoneNumber = 1

  private fun newPlantingSite(
      boundary: Geometry,
      plantingZones: List<NewPlantingZoneModel> = emptyList(),
  ): NewPlantingSiteModel {
    val gridOrigin =
        GeometryFactory(PrecisionModel(), boundary.srid)
            .createPoint(boundary.envelope.coordinates[0])

    return PlantingSiteModel.create(
        areaHa = boundary.calculateAreaHectares(),
        boundary = boundary.toMultiPolygon(),
        gridOrigin = gridOrigin,
        name = "Site",
        organizationId = OrganizationId(1),
        plantingZones = plantingZones,
    )
  }

  fun newPlantingZone(
      boundary: Geometry,
      name: String = "Zone ${nextZoneNumber++}",
      plantingSubzones: List<NewPlantingSubzoneModel> = emptyList(),
  ): NewPlantingZoneModel {
    return NewPlantingZoneModel(
        areaHa = boundary.calculateAreaHectares(),
        boundary = boundary.toMultiPolygon(),
        id = null,
        name = name,
        plantingSubzones = plantingSubzones,
    )
  }

  fun newPlantingSubzone(
      boundary: Geometry,
      name: String = "Subzone ${nextSubzoneNumber++}",
      fullName: String = "Zone $nextZoneNumber-$name",
  ): NewPlantingSubzoneModel {
    return NewPlantingSubzoneModel(
        areaHa = boundary.calculateAreaHectares(),
        boundary = boundary.toMultiPolygon(),
        fullName = fullName,
        id = null,
        name = name,
    )
  }
}
