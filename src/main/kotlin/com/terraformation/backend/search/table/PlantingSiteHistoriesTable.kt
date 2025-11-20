package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_HISTORIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingSiteHistoriesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SITE_HISTORIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          monitoringPlotHistories.asMultiValueSublist(
              "monitoringPlotHistories",
              PLANTING_SITE_HISTORIES.ID.eq(MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID),
          ),
          plantingSites.asSingleValueSublist(
              "plantingSite",
              PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID),
          ),
          plantingZoneHistories.asMultiValueSublist(
              "plantingZoneHistories",
              PLANTING_SITE_HISTORIES.ID.eq(PLANTING_ZONE_HISTORIES.PLANTING_SITE_HISTORY_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", PLANTING_SITE_HISTORIES.BOUNDARY),
          geometryField("exclusion", PLANTING_SITE_HISTORIES.EXCLUSION),
          timestampField("createdTime", PLANTING_SITE_HISTORIES.CREATED_TIME),
          idWrapperField("id", PLANTING_SITE_HISTORIES.ID) { PlantingSiteHistoryId(it) },
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_SUMMARIES)
        .on(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(PLANTING_SITE_HISTORIES.ID)
}
