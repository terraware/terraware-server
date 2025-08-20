package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_CONDITIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ObservationPlotConditionsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = OBSERVATION_PLOT_CONDITIONS.OBSERVATION_PLOT_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          monitoringPlots.asSingleValueSublist(
              "monitoringPlot",
              OBSERVATION_PLOT_CONDITIONS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID),
          ),
          observationPlots.asSingleValueSublist(
              "observationPlot",
              OBSERVATION_PLOT_CONDITIONS.OBSERVATION_PLOT_ID.eq(
                  OBSERVATION_PLOTS.OBSERVATION_PLOT_ID
              ),
          ),
          observations.asSingleValueSublist(
              "observation",
              OBSERVATION_PLOT_CONDITIONS.OBSERVATION_ID.eq(OBSERVATIONS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          enumField("condition", OBSERVATION_PLOT_CONDITIONS.CONDITION_ID),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            OBSERVATION_PLOT_CONDITIONS.OBSERVATION_ID,
            OBSERVATION_PLOT_CONDITIONS.MONITORING_PLOT_ID,
        )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.observations

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(OBSERVATIONS)
        .on(OBSERVATION_PLOT_CONDITIONS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
  }
}
