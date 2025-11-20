package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.PlantingSubzoneHistoryId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_HISTORIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingSubzoneHistoriesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SUBZONE_HISTORIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          monitoringPlotHistories.asMultiValueSublist(
              "monitoringPlotHistories",
              PLANTING_SUBZONE_HISTORIES.ID.eq(
                  MONITORING_PLOT_HISTORIES.PLANTING_SUBZONE_HISTORY_ID
              ),
          ),
          plantingSubzones.asSingleValueSublist(
              "plantingSubzone",
              PLANTING_SUBZONE_HISTORIES.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID),
          ),
          plantingZoneHistories.asSingleValueSublist(
              "plantingZoneHistory",
              PLANTING_SUBZONE_HISTORIES.PLANTING_ZONE_HISTORY_ID.eq(PLANTING_ZONE_HISTORIES.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", PLANTING_SUBZONE_HISTORIES.BOUNDARY),
          textField("fullName", PLANTING_SUBZONE_HISTORIES.FULL_NAME),
          idWrapperField("id", PLANTING_SUBZONE_HISTORIES.ID) { PlantingSubzoneHistoryId(it) },
          textField("name", PLANTING_SUBZONE_HISTORIES.NAME),
          stableIdField("stableId", PLANTING_SUBZONE_HISTORIES.STABLE_ID),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingZoneHistories

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_ZONE_HISTORIES)
        .on(PLANTING_SUBZONE_HISTORIES.PLANTING_ZONE_HISTORY_ID.eq(PLANTING_ZONE_HISTORIES.ID))
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(PLANTING_SUBZONE_HISTORIES.ID)
}
