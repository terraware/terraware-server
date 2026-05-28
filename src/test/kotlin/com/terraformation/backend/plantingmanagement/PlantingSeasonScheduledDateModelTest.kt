package com.terraformation.backend.plantingmanagement

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.SubstratumId
import java.time.LocalDate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlantingSeasonScheduledDateModelTest {

  @Test
  fun `throws IllegalArgumentException when a quantity is less than 0`() {
    val plantingSeasonId = PlantingSeasonId(1)
    val speciesId1 = SpeciesId(2)
    val speciesId2 = SpeciesId(2)
    val substratumId = SubstratumId(3)

    assertThrows<IllegalArgumentException> {
      PlantingSeasonScheduledDateModel(
          plantingSeasonId = plantingSeasonId,
          date = LocalDate.EPOCH,
          species =
              listOf(
                  PlantingSeasonScheduledDateSpecies(
                      quantity = -1,
                      speciesId = speciesId1,
                      substratumId = substratumId,
                  ),
                  PlantingSeasonScheduledDateSpecies(
                      quantity = 1,
                      speciesId = speciesId2,
                      substratumId = substratumId,
                  ),
              ),
      )
    }
  }
}
