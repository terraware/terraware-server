package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.tracking.event.PlantingSeasonEndedEvent
import com.terraformation.backend.tracking.event.PlantingSeasonStartedEvent
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.UpdatedPlantingSeasonModel
import com.terraformation.backend.util.toInstant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class PlantingSiteStoreTransitionSeasonsTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class TransitionPlantingSeasons {
    @Test
    fun `marks planting season as active when its start time arrives`() {
      val startDate = LocalDate.EPOCH.plusDays(1)
      val endDate = startDate.plusDays(60)
      val model =
          store.createPlantingSite(
              PlantingSiteModel.create(
                  name = "name",
                  organizationId = organizationId,
                  timeZone = timeZone,
              ),
              plantingSeasons =
                  listOf(UpdatedPlantingSeasonModel(startDate = startDate, endDate = endDate)),
          )

      assertSeasonActive(false, "Should start as inactive")

      store.transitionPlantingSeasons()

      assertSeasonActive(false, "Should stay inactive until start time arrives")
      eventPublisher.assertEventNotPublished<PlantingSeasonStartedEvent>()

      clock.instant = startDate.toInstant(timeZone)

      store.transitionPlantingSeasons()

      assertSeasonActive(true, "Should transition to active")
      eventPublisher.assertEventPublished(
          PlantingSeasonStartedEvent(model.id, model.plantingSeasons.first().id)
      )

      clock.instant = endDate.minusDays(1).toInstant(timeZone)
      eventPublisher.clear()

      store.transitionPlantingSeasons()
      eventPublisher.assertNoEventsPublished(
          "Should not publish any events if planting season already in correct state"
      )
    }

    @Test
    fun `marks planting season as inactive when its end time arrives`() {
      val startDate = LocalDate.EPOCH.plusDays(1)
      val endDate = startDate.plusDays(60)

      clock.instant = startDate.toInstant(timeZone)

      val model =
          store.createPlantingSite(
              PlantingSiteModel.create(
                  name = "name",
                  organizationId = organizationId,
                  timeZone = timeZone,
              ),
              plantingSeasons =
                  listOf(UpdatedPlantingSeasonModel(startDate = startDate, endDate = endDate)),
          )

      assertSeasonActive(true, "Should start as active")

      store.transitionPlantingSeasons()

      assertSeasonActive(true, "Should remain active until end time arrives")
      eventPublisher.assertEventNotPublished<PlantingSeasonEndedEvent>()

      clock.instant = endDate.plusDays(1).toInstant(timeZone)

      store.transitionPlantingSeasons()

      assertSeasonActive(false, "Should have been marked as inactive")
      eventPublisher.assertEventPublished(
          PlantingSeasonEndedEvent(model.id, model.plantingSeasons.first().id)
      )

      clock.instant = endDate.plusDays(2).toInstant(timeZone)
      eventPublisher.clear()

      store.transitionPlantingSeasons()
      eventPublisher.assertNoEventsPublished(
          "Should not publish any events if planting season already in correct state"
      )
    }

    private fun assertSeasonActive(isActive: Boolean, message: String) {
      assertEquals(listOf(isActive), plantingSeasonsDao.findAll().map { it.isActive }, message)
    }
  }
}
