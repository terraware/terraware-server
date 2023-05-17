package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.ObservationPlotModel
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

class ObservationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val store: ObservationStore by lazy {
    ObservationStore(clock, dslContext, observationsDao, observationPlotsDao)
  }

  private lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    plantingSiteId = insertPlantingSite()

    every { user.canCreateObservation(any()) } returns true
    every { user.canManageObservation(any()) } returns true
    every { user.canReadObservation(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canUpdateObservation(any()) } returns true
  }

  @Nested
  inner class FetchObservationsByPlantingSite {
    @Test
    fun `returns observations in date order`() {
      val startDate1 = LocalDate.of(2021, 4, 1)
      val startDate2 = LocalDate.of(2022, 3, 1)
      val endDate1 = LocalDate.of(2021, 4, 30)
      val endDate2 = LocalDate.of(2022, 3, 31)

      // Insert in reverse time order
      val observationId1 =
          insertObservation(
              endDate = endDate2, startDate = startDate2, state = ObservationState.Upcoming)

      val observationId2 = insertObservation(endDate = endDate1, startDate = startDate1)
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      insertObservationPlot()

      // Observation in a different planting site
      insertPlantingSite()
      insertObservation()

      val expected =
          listOf(
              ExistingObservationModel(
                  endDate = endDate1,
                  id = observationId2,
                  plantingSiteId = plantingSiteId,
                  startDate = startDate1,
                  state = ObservationState.InProgress,
              ),
              ExistingObservationModel(
                  endDate = endDate2,
                  id = observationId1,
                  plantingSiteId = plantingSiteId,
                  startDate = startDate2,
                  state = ObservationState.Upcoming,
              ),
          )

      val actual = store.fetchObservationsByPlantingSite(plantingSiteId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to read planting site`() {
      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> {
        store.fetchObservationsByPlantingSite(plantingSiteId)
      }
    }
  }

  @Nested
  inner class FetchObservationPlotDetails {
    @Test
    fun `calculates correct values from related tables`() {
      val userId1 = UserId(101)
      val userId2 = UserId(102)
      insertUser(userId1, firstName = "First", lastName = "Person")
      insertUser(userId2, firstName = "Second", lastName = "Human")

      insertPlantingZone(name = "Z1")
      val plantingSubzoneId1 = insertPlantingSubzone(fullName = "Z1-S1", name = "S1")

      // A plot that was observed previously and again in this observation
      val monitoringPlotId11 =
          insertMonitoringPlot(boundary = polygon(1.0), fullName = "Z1-S1-1", name = "1")
      insertObservation()
      insertObservationPlot()
      val observationId = insertObservation()
      insertObservationPlot(isPermanent = true)

      // This plot is claimed
      val monitoringPlotId12 =
          insertMonitoringPlot(boundary = polygon(2.0), fullName = "Z1-S1-2", name = "2")
      val claimedTime12 = Instant.ofEpochSecond(12)
      insertObservationPlot(ObservationPlotsRow(claimedBy = userId1, claimedTime = claimedTime12))

      val plantingSubzoneId2 = insertPlantingSubzone(fullName = "Z1-S2", name = "S2")

      // This plot is claimed and completed
      val monitoringPlotId21 =
          insertMonitoringPlot(boundary = polygon(3.0), fullName = "Z1-S2-1", name = "1")
      val claimedTime21 = Instant.ofEpochSecond(210)
      val completedTime21 = Instant.ofEpochSecond(211)
      val observedTime21 = Instant.ofEpochSecond(212)
      insertObservationPlot(
          ObservationPlotsRow(
              claimedBy = userId2,
              claimedTime = claimedTime21,
              completedBy = userId1,
              completedTime = completedTime21,
              notes = "Some notes",
              observedTime = observedTime21))

      val expected =
          listOf(
              AssignedPlotDetails(
                  model =
                      ObservationPlotModel(
                          isPermanent = true,
                          monitoringPlotId = monitoringPlotId11,
                          observationId = observationId,
                      ),
                  boundary = polygon(1.0),
                  claimedByName = null,
                  completedByName = null,
                  isFirstObservation = false,
                  plantingSubzoneId = plantingSubzoneId1,
                  plantingSubzoneName = "Z1-S1",
                  plotName = "Z1-S1-1",
              ),
              AssignedPlotDetails(
                  model =
                      ObservationPlotModel(
                          claimedBy = userId1,
                          claimedTime = claimedTime12,
                          isPermanent = false,
                          monitoringPlotId = monitoringPlotId12,
                          observationId = observationId,
                      ),
                  boundary = polygon(2.0),
                  claimedByName = "First Person",
                  completedByName = null,
                  isFirstObservation = true,
                  plantingSubzoneId = plantingSubzoneId1,
                  plantingSubzoneName = "Z1-S1",
                  plotName = "Z1-S1-2",
              ),
              AssignedPlotDetails(
                  model =
                      ObservationPlotModel(
                          claimedBy = userId2,
                          claimedTime = claimedTime21,
                          completedBy = userId1,
                          completedTime = completedTime21,
                          isPermanent = false,
                          monitoringPlotId = monitoringPlotId21,
                          notes = "Some notes",
                          observationId = observationId,
                          observedTime = observedTime21,
                      ),
                  boundary = polygon(3.0),
                  claimedByName = "Second Human",
                  completedByName = "First Person",
                  isFirstObservation = true,
                  plantingSubzoneId = plantingSubzoneId2,
                  plantingSubzoneName = "Z1-S2",
                  plotName = "Z1-S2-1",
              ))

      val actual = store.fetchObservationPlotDetails(observationId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to read observation`() {
      every { user.canReadObservation(any()) } returns false

      val observationId = insertObservation()

      assertThrows<ObservationNotFoundException> {
        store.fetchObservationPlotDetails(observationId)
      }
    }
  }

  @Nested
  inner class CreateObservation {
    @Test
    fun `saves fields that are relevant to a new observation`() {
      val observationId =
          store.createObservation(
              NewObservationModel(
                  completedTime = Instant.EPOCH,
                  endDate = LocalDate.of(2020, 1, 31),
                  id = null,
                  plantingSiteId = plantingSiteId,
                  startDate = LocalDate.of(2020, 1, 1),
                  state = ObservationState.Completed,
              ))

      val expected =
          ObservationsRow(
              // Completed time should not be saved
              createdTime = clock.instant(),
              endDate = LocalDate.of(2020, 1, 31),
              id = observationId,
              plantingSiteId = plantingSiteId,
              startDate = LocalDate.of(2020, 1, 1),
              stateId = ObservationState.Upcoming,
          )

      val actual = observationsDao.fetchOneById(observationId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canCreateObservation(plantingSiteId) } returns false

      assertThrows<AccessDeniedException> {
        store.createObservation(
            NewObservationModel(
                endDate = LocalDate.EPOCH,
                id = null,
                plantingSiteId = plantingSiteId,
                startDate = LocalDate.EPOCH,
                state = ObservationState.Upcoming,
            ))
      }
    }
  }

  @Nested
  inner class HasPlots {
    @Test
    fun `returns false if observation has no plots`() {
      val observationId = insertObservation()

      assertFalse(store.hasPlots(observationId))
    }

    @Test
    fun `returns true if observation has plots`() {
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      val observationId = insertObservation()
      insertObservationPlot()

      assertTrue(store.hasPlots(observationId))
    }

    @Test
    fun `throws exception if no permission`() {
      val observationId = insertObservation()

      every { user.canReadObservation(observationId) } returns false

      assertThrows<ObservationNotFoundException> { store.hasPlots(observationId) }
    }

    @Test
    fun `throws exception if observation does not exist`() {
      assertThrows<ObservationNotFoundException> { store.hasPlots(ObservationId(1)) }
    }
  }

  @Nested
  inner class UpdateObservationState {
    @Test
    fun `updates state from InProgress to Completed if user has update permission`() {
      val observationId = insertObservation()
      val initial = store.fetchObservationById(observationId)

      every { user.canManageObservation(observationId) } returns false

      store.updateObservationState(observationId, ObservationState.Completed)

      assertEquals(
          initial.copy(completedTime = clock.instant(), state = ObservationState.Completed),
          store.fetchObservationById(observationId))
    }

    @Test
    fun `updates state from Upcoming to InProgress if user has manage permission`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)
      val initial = store.fetchObservationById(observationId)

      store.updateObservationState(observationId, ObservationState.InProgress)

      assertEquals(
          initial.copy(state = ObservationState.InProgress),
          store.fetchObservationById(observationId))
    }

    @Test
    fun `throws exception if no permission to update to Completed`() {
      val observationId = insertObservation()

      every { user.canManageObservation(observationId) } returns false
      every { user.canUpdateObservation(observationId) } returns false

      assertThrows<AccessDeniedException> {
        store.updateObservationState(observationId, ObservationState.Completed)
      }
    }

    @Test
    fun `throws exception if no permission to update to InProgress`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)

      every { user.canManageObservation(observationId) } returns false

      assertThrows<AccessDeniedException> {
        store.updateObservationState(observationId, ObservationState.InProgress)
      }
    }

    @Test
    fun `throws exception on illegal state transition`() {
      val observationId = insertObservation(state = ObservationState.InProgress)

      assertThrows<IllegalArgumentException> {
        store.updateObservationState(observationId, ObservationState.Upcoming)
      }
    }
  }

  @Nested
  inner class AddPlotsToObservation {
    @Test
    fun `honors isPermanent flag`() {
      insertPlantingZone()
      insertPlantingSubzone()
      val permanentPlotId = insertMonitoringPlot(permanentCluster = 1)
      val temporaryPlotId = insertMonitoringPlot(permanentCluster = 2)
      val observationId = insertObservation()

      store.addPlotsToObservation(observationId, listOf(permanentPlotId), isPermanent = true)
      store.addPlotsToObservation(observationId, listOf(temporaryPlotId), isPermanent = false)

      assertEquals(
          mapOf(permanentPlotId to true, temporaryPlotId to false),
          observationPlotsDao.findAll().associate { it.monitoringPlotId to it.isPermanent })
    }

    @Test
    fun `throws exception if same plot is added twice`() {
      insertPlantingZone()
      insertPlantingSubzone()
      val plotId = insertMonitoringPlot()
      val observationId = insertObservation()

      store.addPlotsToObservation(observationId, listOf(plotId), true)

      assertThrows<DuplicateKeyException> {
        store.addPlotsToObservation(observationId, listOf(plotId), false)
      }
    }

    @Test
    fun `throws exception if plots belong to a different planting site`() {
      val observationId = insertObservation()

      insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      val otherSitePlotId = insertMonitoringPlot()

      assertThrows<IllegalStateException> {
        store.addPlotsToObservation(observationId, listOf(otherSitePlotId), true)
      }
    }

    @Test
    fun `throws exception if no permission to manage observation`() {
      val observationId = insertObservation()

      every { user.canManageObservation(observationId) } returns false

      assertThrows<AccessDeniedException> {
        store.addPlotsToObservation(observationId, emptyList(), true)
      }
    }
  }
}
