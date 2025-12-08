package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSiteSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedStratumSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSubstratumSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.point
import java.time.Instant
import java.time.ZoneId
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
   * Returns all the plant totals (plot, substratum, stratum, site) in the database, suitable for
   * use in [assertTotals].
   */
  fun fetchAllTotals(): Set<Any> {
    return (dslContext
            .selectFrom(OBSERVED_PLOT_SPECIES_TOTALS)
            .fetchInto(ObservedPlotSpeciesTotalsRow::class.java) +
            dslContext
                .selectFrom(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
                .fetchInto(ObservedSubstratumSpeciesTotalsRow::class.java) +
            dslContext
                .selectFrom(OBSERVED_STRATUM_SPECIES_TOTALS)
                .fetchInto(ObservedStratumSpeciesTotalsRow::class.java) +
            dslContext
                .selectFrom(OBSERVED_SITE_SPECIES_TOTALS)
                .fetchInto(ObservedSiteSpeciesTotalsRow::class.java))
        .toSet()
  }

  /** Inserts the necessary data to represent a planting site with reported plants. */
  fun insertPlantedSite(
      height: Int = 10,
      width: Int = 10,
      numPermanentPlots: Int = 1,
      numTemporaryPlots: Int = 1,
      plantingCreatedTime: Instant = Instant.EPOCH,
      substratumCompletedTime: Instant? = null,
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
    test.insertStratum(
        height = height,
        numPermanentPlots = numPermanentPlots,
        numTemporaryPlots = numTemporaryPlots,
        width = width,
    )
    test.insertSubstratum(
        height = height,
        plantingCompletedTime = substratumCompletedTime,
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

  data class ObservationStratum(
      val stratumId: StratumId,
      val plots: List<ObservationPlot>,
  )
}
