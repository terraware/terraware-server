package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class StratumHistoriesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = STRATUM_HISTORIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSiteHistories.asSingleValueSublist(
              "plantingSiteHistory",
              STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID),
          ),
          strata.asSingleValueSublist(
              "plantingZone",
              STRATUM_HISTORIES.STRATUM_ID.eq(STRATA.ID),
          ),
          substratumHistories.asMultiValueSublist(
              "plantingSubzoneHistories",
              STRATUM_HISTORIES.ID.eq(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", STRATUM_HISTORIES.BOUNDARY),
          idWrapperField("id", STRATUM_HISTORIES.ID) { StratumHistoryId(it) },
          textField("name", STRATUM_HISTORIES.NAME),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSiteHistories

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_HISTORIES)
        .on(STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(STRATUM_HISTORIES.ID)
}
