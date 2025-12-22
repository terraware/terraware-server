package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.tracking.db.PlantingSubzoneNotFoundException
import com.terraformation.backend.tracking.model.NewObservationModel
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreCreateObservationTest : BaseObservationStoreTest() {
  @Test
  fun `saves fields that are relevant to a new observation`() {
    insertPlantingZone()
    val subzoneId1 = insertPlantingSubzone()
    val subzoneId2 = insertPlantingSubzone()
    insertPlantingSubzone() // Should not be included in observation

    val observationId =
        store.createObservation(
            NewObservationModel(
                completedTime = Instant.EPOCH,
                endDate = LocalDate.of(2020, 1, 31),
                id = null,
                isAdHoc = false,
                observationType = ObservationType.Monitoring,
                plantingSiteId = plantingSiteId,
                requestedSubzoneIds = setOf(subzoneId1, subzoneId2),
                startDate = LocalDate.of(2020, 1, 1),
                state = ObservationState.Completed,
            )
        )

    val expected =
        ObservationsRow(
            // Completed time should not be saved
            createdTime = clock.instant(),
            endDate = LocalDate.of(2020, 1, 31),
            id = observationId,
            isAdHoc = false,
            observationTypeId = ObservationType.Monitoring,
            plantingSiteId = plantingSiteId,
            startDate = LocalDate.of(2020, 1, 1),
            stateId = ObservationState.Upcoming,
        )

    val actual = observationsDao.fetchOneById(observationId)

    assertEquals(expected, actual)

    assertSetEquals(
        setOf(subzoneId1, subzoneId2),
        observationRequestedSubzonesDao.findAll().map { it.substratumId }.toSet(),
        "Subzone IDs",
    )
  }

  @Test
  fun `throws exception if requested subzone is not in correct site`() {
    insertPlantingSite()
    insertPlantingZone()
    val otherSiteSubzoneId = insertPlantingSubzone()

    assertThrows<PlantingSubzoneNotFoundException> {
      store.createObservation(
          NewObservationModel(
              endDate = LocalDate.of(2020, 1, 31),
              id = null,
              isAdHoc = false,
              observationType = ObservationType.Monitoring,
              plantingSiteId = plantingSiteId,
              requestedSubzoneIds = setOf(otherSiteSubzoneId),
              startDate = LocalDate.of(2020, 1, 1),
              state = ObservationState.Upcoming,
          )
      )
    }
  }

  @Test
  fun `throws exception if no permission`() {
    every { user.canCreateObservation(plantingSiteId) } returns false

    assertThrows<AccessDeniedException> {
      store.createObservation(
          NewObservationModel(
              endDate = LocalDate.EPOCH,
              id = null,
              isAdHoc = false,
              observationType = ObservationType.Monitoring,
              plantingSiteId = plantingSiteId,
              startDate = LocalDate.EPOCH,
              state = ObservationState.Upcoming,
          )
      )
    }
  }

  @Test
  fun `throws exception if no permission for ad-hoc observation`() {
    every { user.canScheduleAdHocObservation(plantingSiteId) } returns false

    assertThrows<AccessDeniedException> {
      store.createObservation(
          NewObservationModel(
              endDate = LocalDate.EPOCH,
              id = null,
              isAdHoc = true,
              observationType = ObservationType.Monitoring,
              plantingSiteId = plantingSiteId,
              startDate = LocalDate.EPOCH,
              state = ObservationState.Upcoming,
          )
      )
    }
  }

  @Test
  fun `throws exception for ad-hoc observation with requested subzones`() {
    insertPlantingZone()
    val subzoneId1 = insertPlantingSubzone()
    val subzoneId2 = insertPlantingSubzone()
    assertThrows<IllegalArgumentException> {
      store.createObservation(
          NewObservationModel(
              endDate = LocalDate.EPOCH,
              id = null,
              isAdHoc = true,
              observationType = ObservationType.Monitoring,
              plantingSiteId = plantingSiteId,
              requestedSubzoneIds = setOf(subzoneId1, subzoneId2),
              startDate = LocalDate.EPOCH,
              state = ObservationState.Upcoming,
          )
      )
    }
  }
}
