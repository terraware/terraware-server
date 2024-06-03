package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSiteSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedZoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.point
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals

class ObservationTestHelper(
    private val test: DatabaseTest,
    private val observationStore: ObservationStore,
    private val userId: UserId? = null
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
   * Returns all the plant totals (plot, zone, site) in the database, suitable for use in
   * [assertTotals].
   */
  fun fetchAllTotals(): Set<Any> {
    return (dslContext
            .selectFrom(OBSERVED_PLOT_SPECIES_TOTALS)
            .fetchInto(ObservedPlotSpeciesTotalsRow::class.java) +
            dslContext
                .selectFrom(OBSERVED_ZONE_SPECIES_TOTALS)
                .fetchInto(ObservedZoneSpeciesTotalsRow::class.java) +
            dslContext
                .selectFrom(OBSERVED_SITE_SPECIES_TOTALS)
                .fetchInto(ObservedSiteSpeciesTotalsRow::class.java))
        .toSet()
  }

  /** Inserts the necessary data to represent a planting site with reported plants. */
  fun insertPlantedSite(
      height: Int = 10,
      width: Int = 10,
      numPermanentClusters: Int = 1,
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
        )
    test.insertPlantingZone(
        height = height,
        numPermanentClusters = numPermanentClusters,
        numTemporaryPlots = numTemporaryPlots,
        width = width,
    )
    test.insertPlantingSubzone(
        height = height,
        plantingCompletedTime = subzoneCompletedTime,
        width = width,
    )
    test.insertWithdrawal()
    test.insertDelivery()
    test.insertPlanting(createdTime = plantingCreatedTime)

    return plantingSiteId
  }
}
