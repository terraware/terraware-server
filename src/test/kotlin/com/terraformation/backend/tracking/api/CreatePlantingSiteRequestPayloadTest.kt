package com.terraformation.backend.tracking.api

import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.model.PlantingSiteValidationFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel

class CreatePlantingSiteRequestPayloadTest {
  @Nested
  inner class FindMapProblems {
    /** A polygon whose vertices are all on the same line, so it has no area once repaired. */
    private val collapsedPolygon: Geometry =
        GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
            .createPolygon(
                arrayOf(
                    Coordinate(1.0, 1.0),
                    Coordinate(1.001, 1.0),
                    Coordinate(1.002, 1.0),
                    Coordinate(1.0, 1.0),
                )
            )

    private val siteBoundary = rectangle(1000)

    private fun substratum(name: String = "Substratum", boundary: Geometry = siteBoundary) =
        NewSubstratumPayload(boundary = boundary, name = name)

    private fun stratum(
        name: String = "Stratum",
        boundary: Geometry = siteBoundary,
        substrata: List<NewSubstratumPayload> = listOf(substratum()),
    ) =
        NewStratumPayload(
            boundary = boundary,
            initialPlantingDensity = null,
            name = name,
            substrata = substrata,
            targetPlantDensity = null,
        )

    private fun payload(
        boundary: Geometry? = siteBoundary,
        exclusion: Geometry? = null,
        strata: List<NewStratumPayload> = listOf(stratum()),
    ) =
        CreatePlantingSiteRequestPayload(
            boundary = boundary,
            exclusion = exclusion,
            name = "Site",
            organizationId = OrganizationId(1),
            strata = strata,
            timeZone = null,
        )

    @Test
    fun `returns no problems for valid map`() {
      assertEquals(emptyList<PlantingSiteValidationFailure>(), payload().findMapProblems())
    }

    @Test
    fun `only reports invalid site boundary if it has no area once repaired`() {
      assertEquals(
          listOf(PlantingSiteValidationFailure.siteBoundaryInvalid()),
          payload(boundary = collapsedPolygon, exclusion = rectangle(10)).findMapProblems(),
      )
    }

    @Test
    fun `ignores exclusion that has no area once repaired`() {
      assertEquals(
          listOf(PlantingSiteValidationFailure.exclusionInvalid()),
          payload(exclusion = collapsedPolygon).findMapProblems(),
      )
    }

    @Test
    fun `ignores stratum that has no area once repaired`() {
      assertEquals(
          listOf(PlantingSiteValidationFailure.stratumBoundaryInvalid("Collapsed")),
          payload(
                  strata =
                      listOf(stratum(), stratum(name = "Collapsed", boundary = collapsedPolygon))
              )
              .findMapProblems(),
      )
    }

    @Test
    fun `ignores substratum that has no area once repaired`() {
      assertEquals(
          listOf(PlantingSiteValidationFailure.substratumBoundaryInvalid("Collapsed", "Stratum")),
          payload(
                  strata =
                      listOf(
                          stratum(
                              substrata =
                                  listOf(
                                      substratum(),
                                      substratum(name = "Collapsed", boundary = collapsedPolygon),
                                  )
                          )
                      )
              )
              .findMapProblems(),
      )
    }
  }
}
