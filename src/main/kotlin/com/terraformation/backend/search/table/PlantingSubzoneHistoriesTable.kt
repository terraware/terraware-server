package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingSubzoneHistoriesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SUBSTRATUM_HISTORIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          monitoringPlotHistories.asMultiValueSublist(
              "monitoringPlotHistories",
              SUBSTRATUM_HISTORIES.ID.eq(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID),
          ),
          plantingSubzones.asSingleValueSublist(
              "plantingSubzone",
              SUBSTRATUM_HISTORIES.SUBSTRATUM_ID.eq(SUBSTRATA.ID),
          ),
          plantingZoneHistories.asSingleValueSublist(
              "plantingZoneHistory",
              SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(STRATUM_HISTORIES.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", SUBSTRATUM_HISTORIES.BOUNDARY),
          textField("fullName", SUBSTRATUM_HISTORIES.FULL_NAME),
          idWrapperField("id", SUBSTRATUM_HISTORIES.ID) { SubstratumHistoryId(it) },
          textField("name", SUBSTRATUM_HISTORIES.NAME),
          stableIdField("stableId", SUBSTRATUM_HISTORIES.STABLE_ID),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingZoneHistories

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(STRATUM_HISTORIES)
        .on(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(STRATUM_HISTORIES.ID))
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(SUBSTRATUM_HISTORIES.ID)
}
