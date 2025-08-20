package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.model.ExistingObservationModel
import io.mockk.every
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObservationStoreFetchObservationsByPlantingSiteTest : BaseObservationStoreTest() {
  @Test
  fun `returns observations in date order`() {
    val plantingSiteHistoryId = inserted.plantingSiteHistoryId
    val startDate1 = LocalDate.of(2021, 4, 1)
    val startDate2 = LocalDate.of(2022, 3, 1)
    val startDate3 = LocalDate.of(2023, 3, 1)
    val endDate1 = LocalDate.of(2021, 4, 30)
    val endDate2 = LocalDate.of(2022, 3, 31)
    val endDate3 = LocalDate.of(2023, 3, 31)

    // Ad-hoc observations are excluded by default
    val adHocObservationId =
        insertObservation(
            endDate = endDate3,
            isAdHoc = true,
            startDate = startDate3,
            state = ObservationState.Upcoming,
        )

    // Insert in reverse time order
    val observationId1 =
        insertObservation(
            endDate = endDate2,
            startDate = startDate2,
            state = ObservationState.Upcoming,
        )

    val observationId2 =
        insertObservation(
            endDate = endDate1,
            plantingSiteHistoryId = plantingSiteHistoryId,
            startDate = startDate1,
        )

    insertPlantingZone()
    val subzoneId = insertPlantingSubzone()
    insertMonitoringPlot()
    insertObservationPlot()
    insertObservationRequestedSubzone()

    // Observation in a different planting site
    insertPlantingSite()
    insertObservation()

    val expected =
        listOf(
            ExistingObservationModel(
                endDate = endDate1,
                id = observationId2,
                isAdHoc = false,
                observationType = ObservationType.Monitoring,
                plantingSiteHistoryId = plantingSiteHistoryId,
                plantingSiteId = plantingSiteId,
                requestedSubzoneIds = setOf(subzoneId),
                startDate = startDate1,
                state = ObservationState.InProgress,
            ),
            ExistingObservationModel(
                endDate = endDate2,
                id = observationId1,
                isAdHoc = false,
                observationType = ObservationType.Monitoring,
                plantingSiteId = plantingSiteId,
                startDate = startDate2,
                state = ObservationState.Upcoming,
            ),
        )

    val actual = store.fetchObservationsByPlantingSite(plantingSiteId)

    assertEquals(expected, actual, "Non-ad-hoc observations")

    assertEquals(
        listOf(
            ExistingObservationModel(
                endDate = endDate3,
                id = adHocObservationId,
                isAdHoc = true,
                observationType = ObservationType.Monitoring,
                plantingSiteId = plantingSiteId,
                startDate = startDate3,
                state = ObservationState.Upcoming,
            )
        ),
        store.fetchObservationsByPlantingSite(plantingSiteId, isAdHoc = true),
        "Ad-hoc observations",
    )
  }

  @Test
  fun `throws exception if no permission to read planting site`() {
    every { user.canReadPlantingSite(plantingSiteId) } returns false

    assertThrows<PlantingSiteNotFoundException> {
      store.fetchObservationsByPlantingSite(plantingSiteId)
    }
  }
}
