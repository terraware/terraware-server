package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingSeasonsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SEASONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSites.asSingleValueSublist(
              "plantingSite",
              PLANTING_SEASONS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("endDate", PLANTING_SEASONS.END_DATE),
          idWrapperField("id", PLANTING_SEASONS.ID) { PlantingSeasonId(it) },
          booleanField("isActive", PLANTING_SEASONS.IS_ACTIVE),
          dateField("startDate", PLANTING_SEASONS.START_DATE),
      )

  override val inheritsVisibilityFrom: SearchTable = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_SUMMARIES)
        .on(PLANTING_SEASONS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }
}
