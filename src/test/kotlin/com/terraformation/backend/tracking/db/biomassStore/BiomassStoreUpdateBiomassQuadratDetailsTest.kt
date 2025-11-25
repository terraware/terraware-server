package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratDetailsRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.tracking.event.BiomassQuadratCreatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratDetailsUpdatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratDetailsUpdatedEventValues
import com.terraformation.backend.tracking.event.BiomassQuadratPersistentEvent
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class BiomassStoreUpdateBiomassQuadratDetailsTest : BaseBiomassStoreTest() {
  @BeforeEach
  fun setUpBiomassDetails() {
    insertPlantingZone()
    insertPlantingSubzone()
    insertMonitoringPlot(isAdHoc = true)
    insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(completedBy = user.userId)
    insertObservationBiomassDetails()
    insertObservationBiomassQuadratDetails(
        description = "Original description",
        position = ObservationPlotPosition.NortheastCorner,
    )
  }

  @Test
  fun `updates editable fields of existing quadrat`() {
    val before = dslContext.fetchSingle(OBSERVATION_BIOMASS_QUADRAT_DETAILS)

    store.updateBiomassQuadratDetails(
        inserted.observationId,
        inserted.monitoringPlotId,
        ObservationPlotPosition.NortheastCorner,
    ) {
      it.copy(description = "New description")
    }

    store.updateBiomassDetails(inserted.observationId, inserted.monitoringPlotId) {
      it.copy(
          description = "New description",
          soilAssessment = "New soil assessment",
      )
    }

    val expected = before.copy().apply { description = "New description" }

    assertTableEquals(expected)

    eventPublisher.assertEventPublished(
        BiomassQuadratDetailsUpdatedEvent(
            changedFrom =
                BiomassQuadratDetailsUpdatedEventValues(
                    description = "Original description",
                ),
            changedTo =
                BiomassQuadratDetailsUpdatedEventValues(
                    description = "New description",
                ),
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            position = ObservationPlotPosition.NortheastCorner,
        )
    )
  }

  @Test
  fun `creates quadrat that was not previously present`() {
    val northeastCorner = dslContext.fetchSingle(OBSERVATION_BIOMASS_QUADRAT_DETAILS)

    store.updateBiomassQuadratDetails(
        inserted.observationId,
        inserted.monitoringPlotId,
        ObservationPlotPosition.SouthwestCorner,
    ) {
      it.copy(description = "New description")
    }

    val southwestCorner =
        ObservationBiomassQuadratDetailsRecord(
            description = "New description",
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            positionId = ObservationPlotPosition.SouthwestCorner,
        )

    assertTableEquals(setOf(northeastCorner, southwestCorner))

    eventPublisher.assertEventPublished(
        BiomassQuadratCreatedEvent(
            description = "New description",
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            position = ObservationPlotPosition.SouthwestCorner,
        )
    )
  }

  @Test
  fun `does not publish event or modify database if nothing changed`() {
    val unmodifiedTable = dslContext.fetch(OBSERVATION_BIOMASS_QUADRAT_DETAILS)

    store.updateBiomassQuadratDetails(
        inserted.observationId,
        inserted.monitoringPlotId,
        ObservationPlotPosition.NortheastCorner,
    ) {
      it
    }

    assertTableEquals(unmodifiedTable)

    eventPublisher.assertEventNotPublished<BiomassQuadratPersistentEvent>()
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    every { user.canUpdateObservation(inserted.observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateBiomassQuadratDetails(
          inserted.observationId,
          inserted.monitoringPlotId,
          ObservationPlotPosition.NortheastCorner,
      ) {
        it
      }
    }
  }
}
