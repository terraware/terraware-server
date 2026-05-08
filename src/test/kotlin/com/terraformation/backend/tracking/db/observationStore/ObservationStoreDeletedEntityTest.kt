package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.db.ObservationScenarioTest
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ObservationStoreDeletedEntityTest : ObservationScenarioTest() {
  override val user = mockUser()

  @Test
  fun `observation results retain history ids when stratum and substratum are deleted`() {
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canUpdatePlantingSite(any()) } returns true

    scenario {
      siteCreated {
        stratum(1) { substratum(1) { plot(1) } }
        stratum(2) { substratum(2) { plot(2) } }
      }

      observation(1) {
        plot(1) { species(0, live = 5) }
        plot(2) { species(0, live = 7) }
      }

      val stratum1Id = stratumIds[1]!!
      val stratum2Id = stratumIds[2]!!
      val substratum1Id = substratumIds[1]!!
      val substratum2Id = substratumIds[2]!!
      val observationId = observationIds[1]!!

      val resultsBeforeDelete =
          observationResultsStore.fetchOneById(observationId, ObservationResultsDepth.Plot)

      val stratum1Result = resultsBeforeDelete.strata.first { it.name == "1" }
      val stratum2Result = resultsBeforeDelete.strata.first { it.name == "2" }
      assertEquals(stratum1Id, stratum1Result.stratumId, "Stratum 1 id before delete")
      assertEquals(stratum2Id, stratum2Result.stratumId, "Stratum 2 id before delete")
      assertEquals(
          substratum1Id,
          stratum1Result.substrata.first { it.name == "1" }.substratumId,
          "Substratum 1 id before delete",
      )
      assertEquals(
          substratum2Id,
          stratum2Result.substrata.first { it.name == "2" }.substratumId,
          "Substratum 2 id before delete",
      )

      // Edit the site to remove stratum 2 (along with substratum 2 and plot 2). The omitted
      // entities are deleted by the planting site edit calculator, which cascades to set the
      // entity-id columns on the observed totals to NULL while preserving the history-id
      // columns.
      siteEdited {
        stratum(1) { substratum(1) { plot(1) } }
        stratumDeleted(2)
        substratumDeleted(2)
      }

      // After deletion, observation 1's totals still exist. Stratum 1 retains its entity id;
      // stratum 2 / substratum 2 have null entity ids but their history ids are preserved.
      val resultsAfterDelete =
          observationResultsStore.fetchOneById(observationId, ObservationResultsDepth.Plot)

      val stratum1AfterDelete = resultsAfterDelete.strata.first { it.name == "1" }
      val stratum2AfterDelete = resultsAfterDelete.strata.first { it.name == "2" }

      assertEquals(stratum1Id, stratum1AfterDelete.stratumId, "Stratum 1 id after delete")
      assertNull(stratum2AfterDelete.stratumId, "Deleted stratum 2 id should be null")

      assertEquals(
          substratum1Id,
          stratum1AfterDelete.substrata.first { it.name == "1" }.substratumId,
          "Substratum 1 id after delete",
      )
      assertNull(
          stratum2AfterDelete.substrata.first { it.name == "2" }.substratumId,
          "Deleted substratum 2 id should be null",
      )

      // The corresponding totals rows still exist (the history-id FKs are preserved); only the
      // entity-id columns were set to NULL.
      val stratumTotals =
          test.dslContext
              .selectFrom(OBSERVED_STRATUM_SPECIES_TOTALS)
              .where(OBSERVED_STRATUM_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
              .fetch()

      val stratum1Totals = stratumTotals.filter { it.stratumId == stratum1Id }
      assertEquals(1, stratum1Totals.size, "Stratum 1 should still have its totals row")
      assertNotNull(stratum1Totals.single().stratumHistoryId, "Stratum 1 history id preserved")

      val orphanedStratumTotals = stratumTotals.filter { it.stratumId == null }
      assertEquals(
          1,
          orphanedStratumTotals.size,
          "Deleted stratum 2 should still have its totals row with null stratum_id",
      )
      assertNotNull(
          orphanedStratumTotals.single().stratumHistoryId,
          "Deleted stratum's history id should be preserved",
      )

      val substratumTotals =
          test.dslContext
              .selectFrom(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
              .where(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
              .fetch()

      val substratum1Totals = substratumTotals.filter { it.substratumId == substratum1Id }
      assertEquals(1, substratum1Totals.size, "Substratum 1 should still have its totals row")
      assertNotNull(
          substratum1Totals.single().substratumHistoryId,
          "Substratum 1 history id preserved",
      )

      val orphanedSubstratumTotals = substratumTotals.filter { it.substratumId == null }
      assertEquals(
          1,
          orphanedSubstratumTotals.size,
          "Deleted substratum 2 should still have its totals row with null substratum_id",
      )
      assertNotNull(
          orphanedSubstratumTotals.single().substratumHistoryId,
          "Deleted substratum's history id should be preserved",
      )
    }
  }
}
