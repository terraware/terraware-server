package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSiteSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSubzoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedZoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.point
import java.time.Instant
import java.time.ZoneId
import kotlin.Int
import org.junit.jupiter.api.Assertions.assertEquals

class ObservationTestHelper(
    private val test: DatabaseTest,
    private val observationStore: ObservationStore,
    private val userId: UserId? = null,
) {
  private val dslContext = test.dslContext
  private val effectiveUserId: UserId by lazy { userId ?: currentUser().userId }

  /**
   * Asserts that the contents of the observed totals tables match an expected set of rows. If
   * there's a difference, produces a textual assertion failure so the difference is easy to spot in
   * the test output.
   */
  fun assertTotals(expected: Set<Any>, message: String? = null) {
    val actual = fetchAllTotals()

    if (expected != actual) {
      val expectedRows = expected.map { "$it" }.sorted().joinToString("\n")
      val actualRows = actual.map { "$it" }.sorted().joinToString("\n")
      assertEquals(expectedRows, actualRows, message)
    }
  }

  /**
   * Returns all the plant totals (plot, subzone, zone, site) in the database, suitable for use in
   * [assertTotals].
   */
  fun fetchAllTotals(): Set<Any> {
    return (dslContext
            .selectFrom(OBSERVED_PLOT_SPECIES_TOTALS)
            .fetchInto(ObservedPlotSpeciesTotalsRow::class.java) +
            dslContext
                .selectFrom(OBSERVED_SUBZONE_SPECIES_TOTALS)
                .fetchInto(ObservedSubzoneSpeciesTotalsRow::class.java) +
            dslContext
                .selectFrom(OBSERVED_ZONE_SPECIES_TOTALS)
                .fetchInto(ObservedZoneSpeciesTotalsRow::class.java) +
            dslContext
                .selectFrom(OBSERVED_SITE_SPECIES_TOTALS)
                .fetchInto(ObservedSiteSpeciesTotalsRow::class.java))
        .toSet()
  }

  /**
   * Adds a series of plots to the current observation, each one with some number of recorded plants
   * for some set of species. The goal is to make the scenarios easy to read in the test code.
   */
  fun insertObservationScenario(vararg zones: ObservationZone) {
    zones.forEach { zone ->
      zone.plots.forEach { plot ->
        test.insertObservationPlot(
            claimedBy = effectiveUserId,
            isPermanent = plot.isPermanent,
            monitoringPlotId = plot.plotId,
        )
      }
    }

    observationStore.populateCumulativeDead(test.inserted.observationId)

    zones.forEach { zone ->
      zone.plots.forEach { plot ->
        val recordedPlantsRows =
            plot.plants.flatMap { plant ->
              (List(plant.live) { RecordedPlantStatus.Live } +
                      List(plant.dead) { RecordedPlantStatus.Dead } +
                      List(plant.existing) { RecordedPlantStatus.Existing })
                  .map { status ->
                    RecordedPlantsRow(
                        certaintyId =
                            when {
                              plant.speciesId != null -> RecordedSpeciesCertainty.Known
                              plant.speciesName != null -> RecordedSpeciesCertainty.Other
                              else -> RecordedSpeciesCertainty.Unknown
                            },
                        gpsCoordinates = point(1),
                        speciesId = plant.speciesId,
                        speciesName = plant.speciesName,
                        statusId = status,
                    )
                  }
            }

        observationStore.completePlot(
            conditions = emptySet(),
            monitoringPlotId = plot.plotId,
            notes = null,
            observationId = test.inserted.observationId,
            observedTime = Instant.EPOCH,
            plants = recordedPlantsRows,
        )
      }
    }
  }

  /** Inserts the necessary data to represent a planting site with reported plants. */
  fun insertPlantedSite(
      height: Int = 10,
      width: Int = 10,
      numPermanentPlots: Int = 1,
      numTemporaryPlots: Int = 1,
      plantingCreatedTime: Instant = Instant.EPOCH,
      subzoneCompletedTime: Instant? = null,
      timeZone: ZoneId? = null,
  ): PlantingSiteId {
    if (test.inserted.speciesIds.isEmpty()) {
      test.insertSpecies()
    }
    if (test.inserted.facilityIds.isEmpty()) {
      test.insertFacility(type = FacilityType.Nursery)
    }

    val plantingSiteId =
        test.insertPlantingSite(
            gridOrigin = point(1),
            height = height,
            timeZone = timeZone,
            width = width,
            x = 0,
        )
    test.insertPlantingZone(
        height = height,
        numPermanentPlots = numPermanentPlots,
        numTemporaryPlots = numTemporaryPlots,
        width = width,
    )
    test.insertPlantingSubzone(
        height = height,
        plantingCompletedTime = subzoneCompletedTime,
        width = width,
    )
    test.insertNurseryWithdrawal()
    test.insertDelivery()
    test.insertPlanting(createdTime = plantingCreatedTime)

    return plantingSiteId
  }

  data class PlantTotals(
      val species: Any? = null,
      val live: Int = 0,
      val dead: Int = 0,
      val existing: Int = 0,
  ) {
    val speciesId
      get() = species as? SpeciesId

    val speciesName
      get() = species as? String

    fun plus(other: PlantTotals): PlantTotals {
      return if (species == other.species) {
        PlantTotals(
            species = species,
            live = live + other.live,
            dead = dead + other.dead,
            existing = existing + other.existing,
        )
      } else {
        throw IllegalArgumentException("Cannot add totals of different species")
      }
    }
  }

  data class ObservationPlot(
      val plotId: MonitoringPlotId,
      val plants: List<PlantTotals>,
      val isPermanent: Boolean = true,
  )

  data class ObservationZone(
      val zoneId: PlantingZoneId,
      val plots: List<ObservationPlot>,
  )
}
