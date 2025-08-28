package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITY
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.tracking.event.T0ObservationAssignedEvent
import com.terraformation.backend.tracking.event.T0SpeciesDensityAssignedEvent
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.SpeciesDensityModel
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
  fun fetchT0SiteData(plantingSiteId: PlantingSiteId): List<PlotT0DataModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return with(PLOT_T0_DENSITY) {
      dslContext
          .select(MONITORING_PLOT_ID, SPECIES_ID, PLOT_DENSITY, PLOT_T0_OBSERVATIONS.OBSERVATION_ID)
          .from(this)
          .leftJoin(PLOT_T0_OBSERVATIONS)
          .on(MONITORING_PLOT_ID.eq(PLOT_T0_OBSERVATIONS.MONITORING_PLOT_ID))
          .where(monitoringPlots.PLANTING_SITE_ID.eq(plantingSiteId))
          .fetchGroups(MONITORING_PLOT_ID.asNonNullable())
          .map { (monitoringPlotId, records) ->
            PlotT0DataModel(
                monitoringPlotId = monitoringPlotId,
                observationId = records.first()[PLOT_T0_OBSERVATIONS.OBSERVATION_ID],
                densityData =
                    records.map { record ->
                      SpeciesDensityModel(
                          speciesId = record[SPECIES_ID]!!,
                          plotDensity = record[PLOT_DENSITY]!!,
                      )
                    },
            )
          }
    }
  }

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

  fun assignT0PlotSpeciesDensities(
      monitoringPlotId: MonitoringPlotId,
      densities: List<SpeciesDensityModel>,
  ) {
    requirePermissions { updateT0(monitoringPlotId) }

    if (plotHasObservationT0(monitoringPlotId)) {
      throw IllegalStateException(
          "Cannot assign species density to plot $monitoringPlotId which already has observation assigned"
      )
    }

    dslContext.transaction { _ ->
      with(PLOT_T0_DENSITY) {
        var insertQuery = dslContext.insertInto(this, MONITORING_PLOT_ID, SPECIES_ID, PLOT_DENSITY)

        densities.forEach {
          if (it.plotDensity < BigDecimal.ZERO) {
            throw IllegalArgumentException("Plot density must not be negative")
          }
          insertQuery = insertQuery.values(monitoringPlotId, it.speciesId, it.plotDensity)
        }

        insertQuery.onDuplicateKeyUpdate().set(PLOT_DENSITY, DSL.excluded(PLOT_DENSITY)).execute()
      }

      densities.forEach {
        eventPublisher.publishEvent(
            T0SpeciesDensityAssignedEvent(
                monitoringPlotId = monitoringPlotId,
                speciesId = it.speciesId,
                plotDensity = it.plotDensity,
            )
        )
      }
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
