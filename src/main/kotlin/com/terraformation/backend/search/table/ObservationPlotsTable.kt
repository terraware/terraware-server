package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ObservationPlotsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = OBSERVATION_PLOTS.OBSERVATION_PLOT_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          monitoringPlots.asSingleValueSublist(
              "monitoringPlot", OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID)),
          observations.asSingleValueSublist(
              "observation", OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("claimedTime", OBSERVATION_PLOTS.CLAIMED_TIME),
          timestampField("completedTime", OBSERVATION_PLOTS.COMPLETED_TIME),
          booleanField("isPermanent", OBSERVATION_PLOTS.IS_PERMANENT),
          textField("notes", OBSERVATION_PLOTS.NOTES),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(OBSERVATION_PLOTS.OBSERVATION_ID, OBSERVATION_PLOTS.MONITORING_PLOT_ID)

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.observations

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(OBSERVATIONS).on(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
  }
}
