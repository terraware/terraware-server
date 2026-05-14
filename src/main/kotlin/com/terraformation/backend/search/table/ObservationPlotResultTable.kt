package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ObservationPlotResultTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = OBSERVATION_PLOT_RESULTS.OBSERVATION_PLOT_HISTORY_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          monitoringPlots.asSingleValueSublist(
              "monitoringPlot",
              OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID),
          ),
          monitoringPlotHistories.asSingleValueSublist(
              "monitoringPlotHistory",
              OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_HISTORY_ID.eq(MONITORING_PLOT_HISTORIES.ID),
          ),
          observations.asSingleValueSublist(
              "observation",
              OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID),
          ),
          observationPlots.asSingleValueSublist(
              "observationPlot",
              OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(OBSERVATION_PLOTS.OBSERVATION_ID)
                  .and(
                      OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID.eq(
                          OBSERVATION_PLOTS.MONITORING_PLOT_ID
                      )
                  ),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          integerField("permanentLive", OBSERVATION_PLOT_RESULTS.PERMANENT_LIVE),
          integerField("plantDensity", OBSERVATION_PLOT_RESULTS.PLANT_DENSITY),
          integerField("survivalRate", OBSERVATION_PLOT_RESULTS.SURVIVAL_RATE),
          integerField("totalDead", OBSERVATION_PLOT_RESULTS.TOTAL_DEAD),
          integerField("totalExisting", OBSERVATION_PLOT_RESULTS.TOTAL_EXISTING),
          integerField("totalLive", OBSERVATION_PLOT_RESULTS.TOTAL_LIVE),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            OBSERVATION_PLOT_RESULTS.OBSERVATION_ID,
            OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID,
        )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.observations

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(OBSERVATIONS).on(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
  }
}
