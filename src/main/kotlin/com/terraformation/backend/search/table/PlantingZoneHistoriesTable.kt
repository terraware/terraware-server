package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.PlantingZoneHistoryId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_HISTORIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingZoneHistoriesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_ZONE_HISTORIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSiteHistories.asSingleValueSublist(
              "plantingSiteHistory",
              PLANTING_ZONE_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID),
          ),
          plantingSubzoneHistories.asMultiValueSublist(
              "plantingSubzoneHistories",
              PLANTING_ZONE_HISTORIES.ID.eq(PLANTING_SUBZONE_HISTORIES.PLANTING_ZONE_HISTORY_ID),
          ),
          plantingZones.asSingleValueSublist(
              "plantingZone",
              PLANTING_ZONE_HISTORIES.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", PLANTING_ZONE_HISTORIES.BOUNDARY),
          idWrapperField("id", PLANTING_ZONE_HISTORIES.ID) { PlantingZoneHistoryId(it) },
          textField("name", PLANTING_ZONE_HISTORIES.NAME),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSiteHistories

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_HISTORIES)
        .on(PLANTING_ZONE_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(PLANTING_ZONE_HISTORIES.ID)
}
