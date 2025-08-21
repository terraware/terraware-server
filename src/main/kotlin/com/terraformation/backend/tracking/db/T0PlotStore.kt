package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.T0_PLOT
import com.terraformation.backend.tracking.event.T0ObservationAssignedEvent
import com.terraformation.backend.tracking.event.T0SpeciesDensityAssignedEvent
import jakarta.inject.Named
import java.math.BigDecimal
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

@Named
class T0PlotStore(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {
  fun assignT0PlotObservation(monitoringPlotId: MonitoringPlotId, observationId: ObservationId) {
    requirePermissions { updateT0(monitoringPlotId) }

    with(T0_PLOT) {
      dslContext
          .insertInto(this)
          .set(MONITORING_PLOT_ID, monitoringPlotId)
          .set(OBSERVATION_ID, observationId)
          .onConflict(MONITORING_PLOT_ID)
          .where(OBSERVATION_ID.isNotNull())
          .doUpdate()
          .set(OBSERVATION_ID, observationId)
          .execute()

      eventPublisher.publishEvent(
          T0ObservationAssignedEvent(
              monitoringPlotId = monitoringPlotId,
              observationId = observationId,
          )
      )
    }
  }

  fun assignT0PlotSpeciesDensity(
      monitoringPlotId: MonitoringPlotId,
      speciesId: SpeciesId,
      density: BigDecimal,
  ) {
    requirePermissions { updateT0(monitoringPlotId) }

    if (density <= BigDecimal.ZERO) {
      throw IllegalArgumentException("Density must be above zero")
    }

    with(T0_PLOT) {
      dslContext
          .insertInto(this)
          .set(MONITORING_PLOT_ID, monitoringPlotId)
          .set(SPECIES_ID, speciesId)
          .set(ESTIMATED_PLANTING_DENSITY, density)
          .onDuplicateKeyUpdate()
          .set(ESTIMATED_PLANTING_DENSITY, density)
          .execute()

      eventPublisher.publishEvent(
          T0SpeciesDensityAssignedEvent(
              monitoringPlotId = monitoringPlotId,
              speciesId = speciesId,
              density = density,
          )
      )
    }
  }
}
