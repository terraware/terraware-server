package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.SubstratumId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RateLimitedPlantingSeasonEventsTest {
  private val organizationId = OrganizationId(1)
  private val plantingSeasonId = PlantingSeasonId(1)

  @Nested
  inner class SpeciesChanged {
    private fun event(added: Set<Long> = emptySet(), removed: Set<Long> = emptySet()) =
        RateLimitedPlantingSeasonSpeciesChangedEvent(
            addedSpeciesIds = added.map { SpeciesId(it) }.toSet(),
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            removedSpeciesIds = removed.map { SpeciesId(it) }.toSet(),
        )

    @Test
    fun `combines additions and removals of different species`() {
      assertEquals(
          event(added = setOf(1, 3), removed = setOf(2, 4)),
          event(added = setOf(3), removed = setOf(4))
              .combine(event(added = setOf(1), removed = setOf(2))),
      )
    }

    @Test
    fun `species added and then removed is omitted`() {
      assertEquals(
          event(added = setOf(2)),
          event(removed = setOf(1)).combine(event(added = setOf(1, 2))),
      )
    }

    @Test
    fun `species removed and then added again is omitted`() {
      assertEquals(
          event(removed = setOf(2)),
          event(added = setOf(1)).combine(event(removed = setOf(1, 2))),
      )
    }
  }

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
}
