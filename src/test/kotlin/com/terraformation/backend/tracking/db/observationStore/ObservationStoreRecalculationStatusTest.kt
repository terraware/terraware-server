package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class ObservationStoreRecalculationStatusTest : BaseObservationStoreTest() {
  private lateinit var stratumId: StratumId
  private lateinit var monitoringPlotId: MonitoringPlotId

  @BeforeEach
  fun setUpStratumAndPlot() {
    stratumId = insertStratum()
    insertSubstratum()
    monitoringPlotId = insertMonitoringPlot(permanentIndex = 1)
  }

  @Test
  fun `runRecalculateSurvivalRates by plot clears the dirty flag on a T0-dirty site row`() {
    val modifiedTime = clock.instant().minusSeconds(60)
    insertRecalculationRow(t0ModifiedTime = modifiedTime)

    store.runRecalculateSurvivalRates(monitoringPlotId)

    val row = fetchRecalculationRow()
    assertEquals(modifiedTime, row.lastT0Modified, "last_t0_modified_time should not change")
    assertEquals(clock.instant(), row.lastRecalculated, "last_recalculated_time should be set")
  }

  @Test
  fun `runRecalculateSurvivalRates by plot clears the dirty flag on an observation-dirty site row`() {
    val modifiedTime = clock.instant().minusSeconds(60)
    insertRecalculationRow(observationModifiedTime = modifiedTime)

    store.runRecalculateSurvivalRates(monitoringPlotId)

    val row = fetchRecalculationRow()
    assertEquals(
        modifiedTime,
        row.lastObservationModified,
        "last_observation_modified_time should not change",
    )
    assertEquals(clock.instant(), row.lastRecalculated, "last_recalculated_time should be set")
  }

  @Test
  fun `runRecalculateSurvivalRates by stratum clears the dirty flag on a T0-dirty site row`() {
    val modifiedTime = clock.instant().minusSeconds(60)
    insertRecalculationRow(t0ModifiedTime = modifiedTime)

    store.runRecalculateSurvivalRates(stratumId)

    val row = fetchRecalculationRow()
    assertEquals(modifiedTime, row.lastT0Modified, "last_t0_modified_time should not change")
    assertEquals(clock.instant(), row.lastRecalculated, "last_recalculated_time should be set")
  }

  @Test
  fun `runRecalculateSurvivalRates by plot is a no-op when no site row exists`() {
    store.runRecalculateSurvivalRates(monitoringPlotId)

    assertNull(fetchRecalculationRowOrNull(), "Job must not insert a row when none exists")
  }

  @Test
  fun `runRecalculateSurvivalRates by stratum is a no-op when no site row exists`() {
    store.runRecalculateSurvivalRates(stratumId)

    assertNull(fetchRecalculationRowOrNull(), "Job must not insert a row when none exists")
  }

  @Test
  fun `clean row stays clean when a fresh job runs without any further write`() {
    val modifiedTime = clock.instant().minusSeconds(120)
    insertRecalculationRow(t0ModifiedTime = modifiedTime)

    store.runRecalculateSurvivalRates(monitoringPlotId)
    val afterFirst = fetchRecalculationRow().lastRecalculated
    assertNotNull(afterFirst)

    clock.instant = clock.instant().plusSeconds(60)
    store.runRecalculateSurvivalRates(monitoringPlotId)

    assertEquals(
        clock.instant(),
        fetchRecalculationRow().lastRecalculated,
        "Re-running on a clean row still advances last_recalculated_time to clock.instant()",
    )
  }

  private data class RecalcRow(
      val lastT0Modified: Instant?,
      val lastObservationModified: Instant?,
      val lastRecalculated: Instant?,
  )

  private fun insertRecalculationRow(
      t0ModifiedTime: Instant? = null,
      observationModifiedTime: Instant? = null,
  ) {
    dslContext
        .insertInto(PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS)
        .set(PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS.PLANTING_SITE_ID, plantingSiteId)
        .set(PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS.LAST_T0_MODIFIED_TIME, t0ModifiedTime)
        .set(
            PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS.LAST_OBSERVATION_MODIFIED_TIME,
            observationModifiedTime,
        )
        .execute()
  }

  private fun fetchRecalculationRow(): RecalcRow =
      fetchRecalculationRowOrNull() ?: error("Status row for $plantingSiteId should exist")

  private fun fetchRecalculationRowOrNull(): RecalcRow? =
      dslContext
          .select(
              PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS.LAST_T0_MODIFIED_TIME,
              PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS.LAST_OBSERVATION_MODIFIED_TIME,
              PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS.LAST_RECALCULATED_TIME,
          )
          .from(PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS)
          .where(PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
          .fetchOne {
            RecalcRow(
                it[PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS.LAST_T0_MODIFIED_TIME],
                it[PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS.LAST_OBSERVATION_MODIFIED_TIME],
                it[PLANTING_SITE_SURVIVAL_RATE_RECALCULATIONS.LAST_RECALCULATED_TIME],
            )
          }
}
