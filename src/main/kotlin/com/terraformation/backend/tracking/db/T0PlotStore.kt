package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITY
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.SpeciesDensityModel
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.context.ApplicationEventPublisher

@Named
class T0PlotStore(
    private val clock: InstantSource,
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
          .orderBy(monitoringPlots.PLOT_NUMBER)
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

    val now = clock.instant()
    val currentUserId = currentUser().userId

    dslContext.transaction { _ ->
      with(PLOT_T0_OBSERVATIONS) {
        dslContext
            .insertInto(this)
            .set(MONITORING_PLOT_ID, monitoringPlotId)
            .set(OBSERVATION_ID, observationId)
            .set(CREATED_BY, currentUserId)
            .set(CREATED_TIME, now)
            .set(MODIFIED_BY, currentUserId)
            .set(MODIFIED_TIME, now)
            .onDuplicateKeyUpdate()
            .set(OBSERVATION_ID, observationId)
            .set(MODIFIED_BY, currentUserId)
            .set(MODIFIED_TIME, now)
            .execute()
      }

      with(PLOT_T0_DENSITY) {
        // ensure no leftover densities for species that are not in this observation
        dslContext.deleteFrom(this).where(MONITORING_PLOT_ID.eq(monitoringPlotId)).execute()

        dslContext
            .insertInto(
                this,
                MONITORING_PLOT_ID,
                SPECIES_ID,
                PLOT_DENSITY,
                CREATED_BY,
                CREATED_TIME,
                MODIFIED_BY,
                MODIFIED_TIME,
            )
            .select(
                DSL.select(
                        OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID,
                        OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID,
                        OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_LIVE.plus(
                                OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_DEAD
                            )
                            .cast(SQLDataType.NUMERIC),
                        DSL.inline(currentUserId),
                        DSL.inline(now),
                        DSL.inline(currentUserId),
                        DSL.inline(now),
                    )
                    .from(OBSERVED_PLOT_SPECIES_TOTALS)
                    .where(
                        OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId)
                            .and(
                                OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(monitoringPlotId)
                            )
                            .and(OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID.isNotNull)
                    )
            )
            .onDuplicateKeyUpdate()
            .set(PLOT_DENSITY, DSL.excluded(PLOT_DENSITY))
            .set(MODIFIED_BY, currentUserId)
            .set(MODIFIED_TIME, now)
            .execute()

        eventPublisher.publishEvent(
            T0PlotDataAssignedEvent(
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
            )
        )
      }
    }
  }

  fun assignT0PlotSpeciesDensities(
      monitoringPlotId: MonitoringPlotId,
      densities: List<SpeciesDensityModel>,
  ) {
    requirePermissions { updateT0(monitoringPlotId) }

    val now = clock.instant()
    val currentUserId = currentUser().userId

    dslContext.transaction { _ ->
      with(PLOT_T0_OBSERVATIONS) {
        // delete t0 observations associated with this plot so that retrieval doesn't return
        // incorrect
        // data
        dslContext.deleteFrom(this).where(MONITORING_PLOT_ID.eq(monitoringPlotId)).execute()
      }

      with(PLOT_T0_DENSITY) {
        // ensure no leftover densities for species that are not in this request
        dslContext.deleteFrom(this).where(MONITORING_PLOT_ID.eq(monitoringPlotId)).execute()

        var insertQuery =
            dslContext.insertInto(
                this,
                MONITORING_PLOT_ID,
                SPECIES_ID,
                PLOT_DENSITY,
                CREATED_BY,
                CREATED_TIME,
                MODIFIED_BY,
                MODIFIED_TIME,
            )

        densities.forEach {
          if (it.plotDensity < BigDecimal.ZERO) {
            throw IllegalArgumentException("Plot density must not be negative")
          }
          insertQuery =
              insertQuery.values(
                  monitoringPlotId,
                  it.speciesId,
                  it.plotDensity,
                  currentUserId,
                  now,
                  currentUserId,
                  now,
              )
        }

        insertQuery.execute()
      }

      eventPublisher.publishEvent(T0PlotDataAssignedEvent(monitoringPlotId = monitoringPlotId))
    }
  }
}
