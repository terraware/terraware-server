package com.terraformation.backend.tracking.event

import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.tracking.model.DensityChangedEventModel
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StratumDensityUpdatedEventTest {
  @Nested
  inner class Combine {
    @Test
    fun `combines before and after values per stratum and per density type`() {
      val plantingSiteId = PlantingSiteId(100)
      val stratumId1 = StratumId(1)
      val stratumId2 = StratumId(2)

      val existingEvent =
          StratumDensityUpdatedEvent(
              densityChanges =
                  listOf(
                      DensityChangedEventModel(
                          densityType = DensityChangedEventModel.DensityType.Initial,
                          previousDensity = null,
                          newDensity = BigDecimal(10),
                          stratumId = stratumId1,
                          stratumName = "1",
                      ),
                      DensityChangedEventModel(
                          densityType = DensityChangedEventModel.DensityType.Target,
                          previousDensity = BigDecimal(70),
                          newDensity = BigDecimal(80),
                          stratumId = stratumId2,
                          stratumName = "2",
                      ),
                  ),
              plantingSiteId = plantingSiteId,
          )

      val newEvent =
          StratumDensityUpdatedEvent(
              densityChanges =
                  listOf(
                      // Collision with existing change
                      DensityChangedEventModel(
                          densityType = DensityChangedEventModel.DensityType.Initial,
                          previousDensity = BigDecimal(10),
                          newDensity = BigDecimal(20),
                          stratumId = stratumId1,
                          stratumName = "1",
                      ),
                      // Same stratum as existing change, but different type
                      DensityChangedEventModel(
                          densityType = DensityChangedEventModel.DensityType.Target,
                          previousDensity = BigDecimal(30),
                          newDensity = BigDecimal(40),
                          stratumId = stratumId1,
                          stratumName = "1",
                      ),
                      // Same type as existing change, but different stratum
                      DensityChangedEventModel(
                          densityType = DensityChangedEventModel.DensityType.Initial,
                          previousDensity = BigDecimal(50),
                          newDensity = BigDecimal(60),
                          stratumId = stratumId2,
                          stratumName = "2",
                      ),
                  ),
              plantingSiteId = plantingSiteId,
          )

      val expected =
          StratumDensityUpdatedEvent(
              densityChanges =
                  listOf(
                      DensityChangedEventModel(
                          densityType = DensityChangedEventModel.DensityType.Initial,
                          previousDensity = null,
                          newDensity = BigDecimal(20),
                          stratumId = stratumId1,
                          stratumName = "1",
                      ),
                      DensityChangedEventModel(
                          densityType = DensityChangedEventModel.DensityType.Target,
                          previousDensity = BigDecimal(70),
                          newDensity = BigDecimal(80),
                          stratumId = stratumId2,
                          stratumName = "2",
                      ),
                      DensityChangedEventModel(
                          densityType = DensityChangedEventModel.DensityType.Target,
                          previousDensity = BigDecimal(30),
                          newDensity = BigDecimal(40),
                          stratumId = stratumId1,
                          stratumName = "1",
                      ),
                      DensityChangedEventModel(
                          densityType = DensityChangedEventModel.DensityType.Initial,
                          previousDensity = BigDecimal(50),
                          newDensity = BigDecimal(60),
                          stratumId = stratumId2,
                          stratumName = "2",
                      ),
                  ),
              plantingSiteId = plantingSiteId,
          )

      assertEquals(expected, newEvent.combine(existingEvent))
    }
  }
}
