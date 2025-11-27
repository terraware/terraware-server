package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotConditionsRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_CONDITIONS
import com.terraformation.backend.tracking.db.PlotNotCompletedException
import com.terraformation.backend.tracking.event.ObservationPlotEditedEvent
import com.terraformation.backend.tracking.event.ObservationPlotEditedEventValues
import io.mockk.every
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreUpdateObservationPlotDetailsTest : BaseObservationStoreTest() {
  @Test
  fun `updates editable fields and publishes event`() {
    val observationId = insertObservation()
    val monitoringPlotId = insertMonitoringPlot()
    insertObservationPlot(completedBy = user.userId)
    insertObservationPlotCondition(condition = ObservableCondition.AnimalDamage)
    insertObservationPlotCondition(condition = ObservableCondition.SeedProduction)

    val plotsBefore = dslContext.fetchSingle(OBSERVATION_PLOTS)

    val oldConditions = setOf(ObservableCondition.AnimalDamage, ObservableCondition.SeedProduction)
    val newConditions = setOf(ObservableCondition.Fire, ObservableCondition.SeedProduction)

    store.updateObservationPlotDetails(observationId, monitoringPlotId) {
      it.copy(
          conditions = newConditions,
          notes = "New notes",
      )
    }

    val plotsExpected = plotsBefore.copy().apply { notes = "New notes" }

    assertTableEquals(plotsExpected)
    assertTableEquals(
        newConditions
            .map { ObservationPlotConditionsRecord(observationId, monitoringPlotId, it) }
            .toSet()
    )

    eventPublisher.assertEventPublished(
        ObservationPlotEditedEvent(
            changedFrom =
                ObservationPlotEditedEventValues(conditions = oldConditions, notes = null),
            changedTo =
                ObservationPlotEditedEventValues(conditions = newConditions, notes = "New notes"),
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
        )
    )
  }

  @Test
  fun `leaves conditions alone if not changed`() {
    val observationId = insertObservation()
    val monitoringPlotId = insertMonitoringPlot()
    insertObservationPlot(completedBy = user.userId)
    insertObservationPlotCondition(condition = ObservableCondition.AnimalDamage)
    insertObservationPlotCondition(condition = ObservableCondition.SeedProduction)

    val conditionsBefore = dslContext.fetch(OBSERVATION_PLOT_CONDITIONS)
    val plotsBefore = dslContext.fetchSingle(OBSERVATION_PLOTS)

    store.updateObservationPlotDetails(observationId, monitoringPlotId) {
      it.copy(notes = "New notes")
    }

    val plotsExpected = plotsBefore.copy().apply { notes = "New notes" }

    assertTableEquals(plotsExpected)
    assertTableEquals(conditionsBefore)

    eventPublisher.assertEventPublished(
        ObservationPlotEditedEvent(
            changedFrom = ObservationPlotEditedEventValues(conditions = null, notes = null),
            changedTo = ObservationPlotEditedEventValues(conditions = null, notes = "New notes"),
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
        )
    )
  }

  @Test
  fun `throws exception if plot is not completed yet`() {
    val observationId = insertObservation()
    val monitoringPlotId = insertMonitoringPlot()
    insertObservationPlot()

    assertThrows<PlotNotCompletedException> {
      store.updateObservationPlotDetails(observationId, monitoringPlotId) { it }
    }
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    val observationId = insertObservation()
    val monitoringPlotId = insertMonitoringPlot()
    insertObservationPlot()

    every { user.canUpdateObservation(observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateObservationPlotDetails(observationId, monitoringPlotId) { it }
    }
  }
}
