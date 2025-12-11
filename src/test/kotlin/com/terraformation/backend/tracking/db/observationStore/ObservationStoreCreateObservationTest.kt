package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.tracking.db.SubstrataNotFoundException
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
    val substratumId1 = insertPlantingSubzone()
    val substratumId2 = insertPlantingSubzone()
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
                requestedSubstratumIds = setOf(substratumId1, substratumId2),
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
        setOf(substratumId1, substratumId2),
        observationRequestedSubstrataDao.findAll().map { it.substratumId }.toSet(),
        "Substratum IDs",
    )
  }

  @Test
  fun `throws exception if requested substratum is not in correct site`() {
    insertPlantingSite()
    insertPlantingZone()
    val otherSiteSubstratumId = insertPlantingSubzone()

    assertThrows<SubstrataNotFoundException> {
      store.createObservation(
          NewObservationModel(
              endDate = LocalDate.of(2020, 1, 31),
              id = null,
              isAdHoc = false,
              observationType = ObservationType.Monitoring,
              plantingSiteId = plantingSiteId,
              requestedSubstratumIds = setOf(otherSiteSubstratumId),
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
  fun `throws exception for ad-hoc observation with requested substrata`() {
    insertPlantingZone()
    val substratumId1 = insertPlantingSubzone()
    val substratumId2 = insertPlantingSubzone()
    assertThrows<IllegalArgumentException> {
      store.createObservation(
          NewObservationModel(
              endDate = LocalDate.EPOCH,
              id = null,
              isAdHoc = true,
              observationType = ObservationType.Monitoring,
              plantingSiteId = plantingSiteId,
              requestedSubstratumIds = setOf(substratumId1, substratumId2),
              startDate = LocalDate.EPOCH,
              state = ObservationState.Upcoming,
          )
      )
    }
  }
}
