package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RateLimitedPlantingSeasonEventsTest {
  private val organizationId = OrganizationId(1)
  private val plantingSeasonId = PlantingSeasonId(1)

  @Nested
  inner class TargetQuantitiesChanged {
    private fun change(speciesId: Long, substratumId: Long, oldQuantity: Int, newQuantity: Int) =
        RateLimitedPlantingSeasonTargetQuantitiesChangedEvent.Change(
            newQuantity = newQuantity,
            oldQuantity = oldQuantity,
            speciesId = SpeciesId(speciesId),
            stratumName = "S1",
            substratumId = SubstratumId(substratumId),
            substratumName = "1",
        )

    private fun event(
        vararg changes: RateLimitedPlantingSeasonTargetQuantitiesChangedEvent.Change
    ) =
        RateLimitedPlantingSeasonTargetQuantitiesChangedEvent(
            changes = changes.toList(),
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            plantingSiteId = PlantingSiteId(1),
        )

    @Test
    fun `keeps first old quantity and last new quantity for each target`() {
      assertEquals(
          event(change(1, 1, 5, 20), change(1, 2, 3, 4)),
          event(change(1, 1, 10, 20), change(1, 2, 3, 4)).combine(event(change(1, 1, 5, 10))),
      )
    }

    @Test
    fun `omits targets whose quantity was changed back to its original value`() {
      assertEquals(
          event(change(2, 1, 1, 2)),
          event(change(1, 1, 10, 5)).combine(event(change(1, 1, 5, 10), change(2, 1, 1, 2))),
      )
    }
  }

  @Nested
  inner class PlantingDateRequestSubmitted {
    private fun event(scheduledPlantingDateId: Long) =
        RateLimitedPlantingDateRequestSubmittedEvent(
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            scheduledPlantingDateId = ScheduledPlantingDateId(scheduledPlantingDateId),
        )

    @Test
    fun `uses separate rate limit keys for different planting dates`() {
      assertNotEquals(event(1).getRateLimitKey(), event(2).getRateLimitKey())
    }
  }
}
