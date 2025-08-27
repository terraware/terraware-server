package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITY
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.tracking.event.T0ObservationAssignedEvent
import com.terraformation.backend.tracking.event.T0SpeciesDensityAssignedEvent
import jakarta.inject.Named
import java.math.BigDecimal
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.context.ApplicationEventPublisher

@Named
class T0PlotStore(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {
  fun assignT0PlotObservation(monitoringPlotId: MonitoringPlotId, observationId: ObservationId) {
    requirePermissions { updateT0(monitoringPlotId) }

    with(PLOT_T0_OBSERVATIONS) {
      dslContext
          .insertInto(this)
          .set(MONITORING_PLOT_ID, monitoringPlotId)
          .set(OBSERVATION_ID, observationId)
          .onDuplicateKeyUpdate()
          .set(OBSERVATION_ID, observationId)
          .execute()
    }

    with(PLOT_T0_DENSITY) {
      // ensure no leftover densities for species that are not in this observation
      dslContext.deleteFrom(this).where(MONITORING_PLOT_ID.eq(monitoringPlotId)).execute()

      dslContext
          .insertInto(this, MONITORING_PLOT_ID, SPECIES_ID, PLOT_DENSITY)
          .select(
              DSL.select(
                      OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID,
                      OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID,
                      OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_LIVE.plus(
                              OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_DEAD
                          )
                          .cast(SQLDataType.NUMERIC),
                  )
                  .from(OBSERVED_PLOT_SPECIES_TOTALS)
                  .where(
                      OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId)
                          .and(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(monitoringPlotId))
                  )
          )
          .onDuplicateKeyUpdate()
          .set(PLOT_DENSITY, DSL.excluded(PLOT_DENSITY))
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
      plotDensity: BigDecimal,
  ) {
    requirePermissions { updateT0(monitoringPlotId) }

    if (plotDensity <= BigDecimal.ZERO) {
      throw IllegalArgumentException("Plot density must be positive")
    }

    if (plotHasObservationT0(monitoringPlotId)) {
      throw IllegalStateException(
          "Cannot assign species density to plot $monitoringPlotId which already has observation assigned"
      )
    }

    with(PLOT_T0_DENSITY) {
      dslContext
          .insertInto(this)
          .set(MONITORING_PLOT_ID, monitoringPlotId)
          .set(SPECIES_ID, speciesId)
          .set(PLOT_DENSITY, plotDensity)
          .onDuplicateKeyUpdate()
          .set(PLOT_DENSITY, plotDensity)
          .execute()

      eventPublisher.publishEvent(
          T0SpeciesDensityAssignedEvent(
              monitoringPlotId = monitoringPlotId,
              speciesId = speciesId,
              plotDensity = plotDensity,
          )
      )
    }
  }

  private fun plotHasObservationT0(monitoringPlotId: MonitoringPlotId) =
      with(PLOT_T0_OBSERVATIONS) {
        dslContext.fetchExists(
            this,
            MONITORING_PLOT_ID.eq(monitoringPlotId),
        )
      }
}
