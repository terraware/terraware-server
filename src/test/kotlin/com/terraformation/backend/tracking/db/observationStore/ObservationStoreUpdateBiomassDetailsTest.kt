package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.tracking.event.BiomassDetailsUpdatedEvent
import com.terraformation.backend.tracking.event.BiomassDetailsUpdatedEventValues
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreUpdateBiomassDetailsTest : BaseObservationStoreTest() {
  @BeforeEach
  fun setUpBiomassDetails() {
    insertPlantingZone()
    insertPlantingSubzone()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails(
        description = "Original description",
        soilAssessment = "Original soil assessment",
    )
  }

  @Test
  fun `updates editable fields`() {
    val before = dslContext.fetchSingle(OBSERVATION_BIOMASS_DETAILS)

    store.updateBiomassDetails(inserted.observationId, inserted.monitoringPlotId) {
      it.copy(
          description = "New description",
          soilAssessment = "New soil assessment",
      )
    }

    val expected =
        before.copy().apply {
          description = "New description"
          soilAssessment = "New soil assessment"
        }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        BiomassDetailsUpdatedEvent(
            changedFrom =
                BiomassDetailsUpdatedEventValues(
                    description = "Original description",
                    soilAssessment = "Original soil assessment",
                ),
            changedTo =
                BiomassDetailsUpdatedEventValues(
                    description = "New description",
                    soilAssessment = "New soil assessment",
                ),
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
        )
    )
  }

  @Test
  fun `updates only description when soil assessment unchanged`() {
    val before = dslContext.fetchSingle(OBSERVATION_BIOMASS_DETAILS)

    store.updateBiomassDetails(inserted.observationId, inserted.monitoringPlotId) {
      it.copy(description = "New description")
    }

    val expected = before.copy().apply { description = "New description" }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        BiomassDetailsUpdatedEvent(
            changedFrom =
                BiomassDetailsUpdatedEventValues(
                    description = "Original description",
                    soilAssessment = null,
                ),
            changedTo =
                BiomassDetailsUpdatedEventValues(
                    description = "New description",
                    soilAssessment = null,
                ),
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
        )
    )
  }

  @Test
  fun `updates only soil assessment when description unchanged`() {
    val before = dslContext.fetchSingle(OBSERVATION_BIOMASS_DETAILS)

    store.updateBiomassDetails(inserted.observationId, inserted.monitoringPlotId) {
      it.copy(soilAssessment = "New soil assessment")
    }

    val expected = before.copy().apply { soilAssessment = "New soil assessment" }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        BiomassDetailsUpdatedEvent(
            changedFrom =
                BiomassDetailsUpdatedEventValues(
                    description = null,
                    soilAssessment = "Original soil assessment",
                ),
            changedTo =
                BiomassDetailsUpdatedEventValues(
                    description = null,
                    soilAssessment = "New soil assessment",
                ),
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
        )
    )
  }

  @Test
  fun `does not publish event or modify database if nothing changed`() {
    val unmodifiedTable = dslContext.fetch(OBSERVATION_BIOMASS_DETAILS)

    store.updateBiomassDetails(inserted.observationId, inserted.monitoringPlotId) { it }

    assertTableEquals(unmodifiedTable)

    eventPublisher.assertEventNotPublished<BiomassDetailsUpdatedEvent>()
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    every { user.canUpdateObservation(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateBiomassDetails(inserted.observationId, inserted.monitoringPlotId) { it }
    }
  }
}
