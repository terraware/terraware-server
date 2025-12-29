package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class MonitoringPlotHistoriesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = MONITORING_PLOT_HISTORIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          users.asSingleValueSublist(
              "createdBy",
              MONITORING_PLOT_HISTORIES.CREATED_BY.eq(USERS.ID),
          ),
          monitoringPlots.asSingleValueSublist(
              "monitoringPlot",
              MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID),
          ),
          plantingSites.asSingleValueSublist(
              "plantingSite",
              MONITORING_PLOT_HISTORIES.PLANTING_SITE_ID.eq(PLANTING_SITES.ID),
          ),
          plantingSiteHistories.asSingleValueSublist(
              "plantingSiteHistory",
              MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID),
          ),
          substrata.asSingleValueSublist(
              "plantingSubzone",
              MONITORING_PLOT_HISTORIES.SUBSTRATUM_ID.eq(SUBSTRATA.ID),
          ),
          substrata.asSingleValueSublist(
              "substratum",
              MONITORING_PLOT_HISTORIES.SUBSTRATUM_ID.eq(SUBSTRATA.ID),
          ),
          substratumHistories.asSingleValueSublist(
              "plantingSubzoneHistory",
              MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID),
          ),
          substratumHistories.asSingleValueSublist(
              "substratumHistory",
              MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("createdTime", MONITORING_PLOT_HISTORIES.CREATED_TIME),
          idWrapperField("id", MONITORING_PLOT_HISTORIES.ID) { MonitoringPlotHistoryId(it) },
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.monitoringPlots

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(MONITORING_PLOTS)
        .on(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(MONITORING_PLOT_HISTORIES.ID)
}
